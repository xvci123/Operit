package com.ai.assistance.operit.core.agent.jobs

import android.content.Context
import com.ai.assistance.operit.core.agent.wake.WakeEvent
import com.ai.assistance.operit.core.agent.wake.WakeRouter
import com.ai.assistance.operit.core.tools.system.AndroidShellExecutor
import com.ai.assistance.operit.core.tools.system.shell.ShellProcess
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hermes 后台作业系统（P1）。
 *
 * 解决的问题：AI 的一条命令只能在一次工具调用里同步跑完，跑得久就占住整个对话轮次，
 * 中途既看不到进度，也没法边跑边做别的事。作业系统把命令丢到独立协程里，命令的
 * stdout/stderr 写进有界缓冲区并落盘，AI 之后用 `job_output` 取输出、用 `job_kill` 终止。
 *
 * 与 shell 通道的关系：作业走 [AndroidShellExecutor.startShellProcess]，和前台 `execute_shell`
 * 走同一套权限阶梯（STANDARD / ACCESSIBILITY / DEBUGGER / ADMIN / ROOT）与严格模式判定，
 * 不另开旁路。
 *
 * 三条语义约定（刻意如此，不是缺陷）：
 *  1. 作业**不限时**——用户明确要求命令不设时间上限。看门狗只在调用方显式传超时值时启用。
 *  2. 作业**随进程死亡**。进程被杀时仍在跑的作业，下次启动读取索引时标为 INTERRUPTED，
 *     并注明「结果未知」，**不会自动重跑**（重跑等于擅自执行未确认的操作）。
 *  3. 输出是**有界**的：内存里最多留 head 32K + tail 32K 字符，超出部分记一个省略计数；
 *     日志文件也按这个上限重写，不会把用户存储写满。
 *
 * 存储位置：`filesDir/hermes/jobs/index.json`（作业清单）+ `<jobId>.log`（输出快照）。
 * 刻意不用 Room：本项目 `exportSchema=false` 且 20 段手写迁移，改实体只在运行时炸。
 */
object JobManager {

    private const val TAG = "JobManager"

    /** 输出缓冲的头部上限（字符）。 */
    const val HEAD_LIMIT_CHARS = 32 * 1024

    /** 输出缓冲的尾部上限（字符）。 */
    const val TAIL_LIMIT_CHARS = 32 * 1024

    /** 清单最多保留多少条作业，超出后淘汰最旧的（连带删日志）。 */
    private const val MAX_RECORDS = 100

    /** 落盘节流：输出很密的作业不会每条都写盘。 */
    private const val FLUSH_INTERVAL_MS = 500L

    /** `job_output` 等待的默认值与上限。等待本身不是命令超时，只影响这一次取输出。 */
    const val DEFAULT_WAIT_MS = 30_000L
    const val MAX_WAIT_MS = 600_000L

    /** `job_output` 单次返回的字符数。 */
    const val DEFAULT_MAX_CHARS = 16_000
    const val MIN_MAX_CHARS = 200
    const val MAX_MAX_CHARS = 64 * 1024

    private const val INDEX_FILE = "index.json"

    /** 作业状态。INTERRUPTED 专指「进程死了，作业没结束」，与 FAILED 区分。 */
    enum class JobStatus {
        RUNNING,
        SUCCEEDED,
        FAILED,
        KILLED,
        INTERRUPTED
    }

    /** 一条作业的记录。`note` 存错误原因或终止原因。 */
    data class JobRecord(
            val id: String,
            val title: String,
            val command: String,
            var status: JobStatus,
            val startedAt: Long,
            var endedAt: Long? = null,
            var exitCode: Int? = null,
            var note: String = "",
            /** 发起这个作业的会话。结算时要把唤醒投回这里；null 表示不叫醒任何人。 */
            val ownerChatId: String? = null,
            /** 结果已经被直接取走过（job_output 拿到了终态），结束后不再唤醒。 */
            var awaited: Boolean = false
    ) {
        fun toJson(): JSONObject =
                JSONObject().apply {
                    put("id", id)
                    put("title", title)
                    put("command", command)
                    put("status", status.name)
                    put("startedAt", startedAt)
                    put("endedAt", endedAt ?: JSONObject.NULL)
                    put("exitCode", exitCode ?: JSONObject.NULL)
                    put("note", note)
                    put("ownerChatId", ownerChatId ?: JSONObject.NULL)
                    put("awaited", awaited)
                }

        companion object {
            fun fromJson(obj: JSONObject): JobRecord =
                    JobRecord(
                            id = obj.getString("id"),
                            title = obj.optString("title"),
                            command = obj.optString("command"),
                            status =
                                    try {
                                        JobStatus.valueOf(obj.getString("status"))
                                    } catch (e: Exception) {
                                        JobStatus.INTERRUPTED
                                    },
                            startedAt = obj.optLong("startedAt"),
                            endedAt = if (obj.isNull("endedAt")) null else obj.getLong("endedAt"),
                            exitCode = if (obj.isNull("exitCode")) null else obj.getInt("exitCode"),
                            note = obj.optString("note"),
                            ownerChatId =
                                    if (obj.isNull("ownerChatId")) null
                                    else obj.optString("ownerChatId").takeIf { it.isNotBlank() },
                            awaited = obj.optBoolean("awaited", false)
                    )
        }
    }

    /**
     * 有界输出缓冲：头部满后转入尾部环形区，中间被挤掉的部分只记数量。
     *
     * 为什么不做无界缓冲：长时间运行的命令（logcat、循环探测）可以在几分钟内产出几百 MB，
     * 全量留在内存里会直接 OOM。
     */
    class OutputBuffer(
            private val headLimit: Int = HEAD_LIMIT_CHARS,
            private val tailLimit: Int = TAIL_LIMIT_CHARS
    ) {
        private val head = StringBuilder()
        private val tail = StringBuilder()
        private var droppedChars = 0L

        @Synchronized
        fun append(text: String) {
            if (text.isEmpty()) return
            val room = headLimit - head.length
            if (room <= 0) {
                appendTail(text)
                return
            }
            if (text.length <= room) {
                head.append(text)
                return
            }
            head.append(text, 0, room)
            appendTail(text.substring(room))
        }

        private fun appendTail(text: String) {
            tail.append(text)
            val overflow = tail.length - tailLimit
            if (overflow > 0) {
                tail.delete(0, overflow)
                droppedChars += overflow.toLong()
            }
        }

        @Synchronized
        fun render(): String {
            if (droppedChars <= 0L) return head.toString() + tail.toString()
            return buildString {
                append(head)
                append("\n…（中间省略 ")
                append(droppedChars)
                append(" 个字符）…\n")
                append(tail)
            }
        }

        @Synchronized
        fun totalChars(): Long = head.length.toLong() + droppedChars + tail.length.toLong()
    }

    /** 正在跑的作业，持有进程句柄与协程句柄，用于 kill 与「等它结束」。 */
    private class LiveJob(
            val context: Context,
            val record: JobRecord,
            val buffer: OutputBuffer
    ) {
        @Volatile var process: ShellProcess? = null
        @Volatile var coroutine: Job? = null
        @Volatile var lastFlushAt: Long = 0L
        val finished = CompletableDeferred<Unit>()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val records = LinkedHashMap<String, JobRecord>()
    private val liveJobs = HashMap<String, LiveJob>()

    @Volatile private var loaded = false
    private var idSeq = 0

    // ==================== 路径与持久化 ====================

    private fun jobsDir(context: Context): File =
            File(File(context.filesDir, "hermes"), "jobs").apply { mkdirs() }

    private fun logFile(context: Context, id: String): File = File(jobsDir(context), "$id.log")

    private fun indexPath(context: Context): File = File(jobsDir(context), INDEX_FILE)

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val dir = jobsDir(context)
            val file = File(dir, INDEX_FILE)
            var recovered = false
            if (file.exists()) {
                try {
                    val arr = JSONArray(file.readText())
                    for (i in 0 until arr.length()) {
                        val record = JobRecord.fromJson(arr.getJSONObject(i))
                        // 进程重启后仍在 RUNNING 的作业，说明上次是中断死的：记账，不重跑。
                        if (record.status == JobStatus.RUNNING) {
                            record.status = JobStatus.INTERRUPTED
                            record.note = "应用进程结束时该作业仍在运行，结果未知；不会自动重跑"
                            recovered = true
                        }
                        records[record.id] = record
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "读取作业索引失败：${file.absolutePath}", e)
                }
            }
            loaded = true
            // 把「上次进程留下的 RUNNING」立刻改写成 INTERRUPTED，免得下次读到的还是旧状态。
            if (recovered) persistIndex(context)
        }
    }

    private fun persistIndex(context: Context) {
        synchronized(lock) {
            while (records.size > MAX_RECORDS) {
                val oldest = records.keys.first()
                records.remove(oldest)
                logFile(context, oldest).delete()
            }
            val arr = JSONArray()
            records.values.forEach { arr.put(it.toJson()) }
            try {
                indexPath(context).writeText(arr.toString())
            } catch (e: Exception) {
                AppLogger.e(TAG, "写作业索引失败", e)
            }
        }
    }

    private fun flushLog(live: LiveJob) {
        // stdout / stderr 两条协程都会走到这里，串行化避免两个线程同时截断同一个文件。
        synchronized(live) {
            try {
                logFile(live.context, live.record.id).writeText(live.buffer.render())
            } catch (e: Exception) {
                AppLogger.w(TAG, "写作业日志失败：${live.record.id}", e)
            }
        }
    }

    // ==================== 作业生命周期 ====================

    private fun nextId(): String {
        synchronized(lock) {
            while (true) {
                idSeq += 1
                val candidate = "job-${System.currentTimeMillis().toString(36)}-${idSeq % 1000}"
                if (!records.containsKey(candidate) && !liveJobs.containsKey(candidate)) {
                    return candidate
                }
            }
        }
    }

    private fun defaultTitle(command: String): String {
        val firstLine = command.lineSequence().firstOrNull().orEmpty().trim()
        return if (firstLine.length <= 48) firstLine else firstLine.substring(0, 48) + "…"
    }

    /**
     * 启动一个后台作业，立即返回（不等待命令结束）。
     *
     * @return 已写入索引的记录
     */
    fun start(
            context: Context,
            command: String,
            title: String?,
            ownerChatId: String? = null
    ): JobRecord {
        ensureLoaded(context)
        val appContext = context.applicationContext
        val record =
                JobRecord(
                        id = nextId(),
                        title = title?.trim()?.takeIf { it.isNotEmpty() } ?: defaultTitle(command),
                        command = command,
                        status = JobStatus.RUNNING,
                        startedAt = System.currentTimeMillis(),
                        // 工具层拿不到会话 id，靠 TurnContext 记录"最近真正开跑的会话"
                        ownerChatId = ownerChatId
                )
        val live = LiveJob(appContext, record, OutputBuffer())
        synchronized(lock) { records[record.id] = record }
        synchronized(lock) { liveJobs[record.id] = live }
        persistIndex(appContext)
        live.coroutine = scope.launch { runJob(live) }
        AppLogger.i(TAG, "后台作业已启动：${record.id} -> ${record.command}")
        return record
    }

    private suspend fun runJob(live: LiveJob) {
        val record = live.record
        try {
            // 与前台 execute_shell 同一套通道解析（含严格模式），不新开旁路。
            val process = AndroidShellExecutor.startShellProcess(record.command)
            live.process = process
            var exitCode: Int? = null
            coroutineScope {
                launch {
                    process.stdout.collect { line -> appendOutput(live, line + "\n") }
                }
                launch {
                    process.stderr.collect { line -> appendOutput(live, line + "\n") }
                }
                exitCode = process.waitFor()
            }
            synchronized(lock) {
                if (record.status == JobStatus.RUNNING) {
                    record.exitCode = exitCode
                    record.status =
                            if (exitCode == 0) JobStatus.SUCCEEDED else JobStatus.FAILED
                }
            }
        } catch (e: CancellationException) {
            // job_kill 主动取消：状态由 kill 写入，这里不覆盖，也不要向上抛。
            AppLogger.i(TAG, "后台作业被取消：${record.id}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "后台作业执行失败：${record.id}", e)
            synchronized(lock) {
                if (record.status == JobStatus.RUNNING) {
                    record.status = JobStatus.FAILED
                    record.note = e.message ?: e.javaClass.simpleName
                }
            }
        } finally {
            synchronized(lock) {
                if (record.endedAt == null) record.endedAt = System.currentTimeMillis()
                live.process = null
            }
            flushLog(live)
            persistIndex(live.context)
            maybeWakeOwner(live)
            live.finished.complete(Unit)
        }
    }

    /**
     * 作业结束 → 给发起它的会话投一条唤醒。
     *
     * 三种情况不叫醒：被 job_kill 终止（人已经不想要结果了）、没有 owner、
     * 结果已经被 job_output 直接收走（awaited 位，抄 DSHA 的规则，免得同一次结算既读又唤醒）。
     */
    private fun maybeWakeOwner(live: LiveJob) {
        val record = live.record
        try {
            if (record.status == JobStatus.KILLED) return
            if (record.awaited) {
                AppLogger.d(TAG, "作业结果已被直接取走，不再唤醒：${record.id}")
                return
            }
            val owner = record.ownerChatId
            if (owner.isNullOrBlank()) {
                AppLogger.d(TAG, "作业没有 owner，不唤醒：${record.id}")
                return
            }
            val elapsed =
                    formatDuration((record.endedAt ?: System.currentTimeMillis()) - record.startedAt)
            val event =
                    WakeEvent.forJobSettled(
                            jobId = record.id,
                            ownerChatId = owner,
                            title = record.title,
                            command = record.command,
                            statusText = statusLabel(record),
                            elapsedText = elapsed,
                            note = record.note
                    )
            WakeRouter.submit(live.context, event)
        } catch (e: Exception) {
            // 唤醒失败不能影响作业本身记账
            AppLogger.w(TAG, "作业结算唤醒投递失败：${record.id}", e)
        }
    }

    private fun appendOutput(live: LiveJob, text: String) {
        live.buffer.append(text)
        val now = System.currentTimeMillis()
        if (now - live.lastFlushAt >= FLUSH_INTERVAL_MS) {
            live.lastFlushAt = now
            flushLog(live)
        }
    }

    /**
     * 终止一个作业。
     *
     * @return 给人看的结果说明（成功、已结束、不存在三种）
     */
    fun kill(context: Context, id: String, reason: String?): String {
        ensureLoaded(context)
        val live = synchronized(lock) { liveJobs[id] }
        if (live == null) {
            val record = synchronized(lock) { records[id] }
            return if (record == null) {
                "作业 $id 不存在。用 job_output 不带 job_id 可以列出全部作业。"
            } else {
                "作业 $id 已经结束（状态：${record.status.name}），没有可终止的进程。"
            }
        }
        val note = reason?.trim()?.takeIf { it.isNotEmpty() } ?: "被 job_kill 终止"
        synchronized(lock) {
            live.record.status = JobStatus.KILLED
            live.record.note = note
            live.record.endedAt = System.currentTimeMillis()
        }
        try {
            live.process?.destroy()
        } catch (e: Exception) {
            AppLogger.w(TAG, "终止作业进程失败：$id", e)
        }
        live.coroutine?.cancel()
        flushLog(live)
        persistIndex(live.context)
        live.finished.complete(Unit)
        synchronized(lock) { liveJobs.remove(id) }
        AppLogger.i(TAG, "作业已终止：$id（$note）")
        return "作业 $id 已终止：$note"
    }

    // ==================== 读取 ====================

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private fun formatTime(ms: Long?): String =
            if (ms == null || ms <= 0L) "—" else synchronized(timeFormat) { timeFormat.format(Date(ms)) }

    private fun formatDuration(ms: Long): String {
        if (ms < 1000) return "${ms}ms"
        val seconds = ms / 1000
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m${seconds % 60}s"
        return "${minutes / 60}h${minutes % 60}m"
    }

    private fun statusLabel(record: JobRecord): String =
            when (record.status) {
                JobStatus.RUNNING -> "RUNNING（运行中）"
                JobStatus.SUCCEEDED -> "SUCCEEDED（成功）"
                JobStatus.FAILED -> "FAILED（失败）"
                JobStatus.KILLED -> "KILLED（已终止）"
                JobStatus.INTERRUPTED -> "INTERRUPTED（进程中断，结果未知）"
            }

    private fun currentBody(context: Context, record: JobRecord): String {
        val live = synchronized(lock) { liveJobs[record.id] }
        if (live != null) return live.buffer.render()
        val file = logFile(context, record.id)
        return if (file.exists()) file.readText() else ""
    }

    /**
     * 读取作业输出。
     *
     * @param waitMs 作业仍在运行时最多等多久（0 表示立即返回）
     * @param maxChars 返回正文的最大字符数
     * @param fromHead true 保留开头（看启动报错），false 保留结尾（看最新进度）
     */
    suspend fun read(
            context: Context,
            id: String,
            waitMs: Long,
            maxChars: Int,
            fromHead: Boolean,
            sinceOffset: Int? = null
    ): String {
        ensureLoaded(context)
        val record = synchronized(lock) { records[id] }
                ?: return "作业 $id 不存在。用 job_output 不带 job_id 可以列出全部作业。"
        val limit = maxChars.coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS)
        val effectiveWait = waitMs.coerceIn(0L, MAX_WAIT_MS)

        if (record.status == JobStatus.RUNNING && effectiveWait > 0L) {
            val live = synchronized(lock) { liveJobs[id] }
            if (live != null) {
                withTimeoutOrNull(effectiveWait) { live.finished.await() }
            }
        }

        val statusAtRead = synchronized(lock) { record.status }
        val endedAt = synchronized(lock) { record.endedAt }
        val exitCode = synchronized(lock) { record.exitCode }
        val note = synchronized(lock) { record.note }

        // 结果已经被取到手了：把 awaited 位立起来，作业真结束时不再补一条唤醒。
        // 同时把可能已经排队的那条唤醒作废（读和结算之间差几毫秒的情况）。
        if (statusAtRead != JobStatus.RUNNING) {
            if (!record.awaited) {
                record.awaited = true
                persistIndex(context)
            }
            WakeRouter.markConsumed(context, "job:" + record.id + ":settled")
        }

        val body = currentBody(context, record)
        val total = body.length

        // D-11：可选增量读。传了 since_offset 就只回这一段，并告诉它下次传多少——
        // 省 token，也不会像"只给尾部"那样漏掉中间。不传仍然是全量快照（现状不变）。
        if (sinceOffset != null) {
            val from = sinceOffset.coerceIn(0, total)
            val to = (from + limit).coerceAtMost(total)
            val chunk = if (total == 0 || to <= from) "" else body.substring(from, to)
            val remain = total - to
            val incrementalHeader =
                    buildString {
                        append("作业 ").append(record.id).append('
')
                        append("状态：").append(statusLabel(record)).append('
')
                        if (total == 0) {
                            append("输出：（暂无输出）\n")
                        } else {
                            append("输出（第 ").append(from).append(" 到 ").append(to)
                            append(" 字符，共 ").append(total).append(" 字符）：\n")
                        }
                        append("----\n")
                    }
            val nextHint =
                    when {
                        total == 0 -> "\n----\n（还没有输出；作业结束后再来取）"
                        remain > 0 ->
                                "\n----\n（后面还有 " + remain + " 字符；下次传 since_offset=" + to + " 接着取）"
                        else -> "\n----\n（已读到本次输出末尾；下次传 since_offset=" + total + " 只取新增）"
                    }
            return incrementalHeader + chunk + nextHint
        }
        val truncated =
                if (total <= limit) body
                else if (fromHead) body.substring(0, limit)
                else body.substring(total - limit)

        val header =
                buildString {
                    append("作业 ").append(record.id).append('\n')
                    append("状态：").append(statusLabel(record)).append('\n')
                    append("命令：").append(record.command).append('\n')
                    append("开始：").append(formatTime(record.startedAt))
                    append("（已运行 ")
                    append(
                            formatDuration(
                                    (endedAt ?: System.currentTimeMillis()) - record.startedAt
                            )
                    )
                    append("）\n")
                    append("结束：").append(formatTime(endedAt))
                    append("　退出码：").append(exitCode?.toString() ?: "—").append('\n')
                    if (note.isNotBlank()) append("备注：").append(note).append('\n')
                    when {
                        total == 0 -> append("输出：（暂无输出）\n")
                        total <= limit -> append("输出（共 ").append(total).append(" 字符）：\n")
                        fromHead ->
                                append("输出（共 ")
                                        .append(total)
                                        .append(" 字符，以下为开头 ")
                                        .append(limit)
                                        .append(" 字符）：\n")
                        else ->
                                append("输出（共 ")
                                        .append(total)
                                        .append(" 字符，以下为最后 ")
                                        .append(limit)
                                        .append(" 字符）：\n")
                    }
                    append("----\n")
                }

        val tailNote =
                when {
                    statusAtRead == JobStatus.RUNNING ->
                            "\n----\n（作业仍在运行；继续用 job_output 取后续输出）"
                    statusAtRead == JobStatus.INTERRUPTED ->
                            "\n----\n（进程结束时作业未结束，以上输出可能不完整；作业不会自动重跑）"
                    else -> ""
                }

        return header + truncated + tailNote
    }

    /** 列出全部作业（不含正文），最多列最近 20 条。 */
    fun list(context: Context): String {
        ensureLoaded(context)
        val all = synchronized(lock) { records.values.toList() }
        if (all.isEmpty()) return "当前没有任何后台作业。用 run_in_background 启动一个。"
        val recent = all.takeLast(20)
        return buildString {
            append("共 ").append(all.size).append(" 个作业")
            if (all.size > recent.size) append("（只列最近 ").append(recent.size).append(" 个）")
            append("：\n")
            recent.forEach { record ->
                val elapsed = formatDuration((record.endedAt ?: System.currentTimeMillis()) - record.startedAt)
                append(record.id)
                append("  ")
                append(record.status.name)
                append("  ")
                append(elapsed)
                append("  ")
                append(record.title)
                if (record.note.isNotBlank()) {
                    append("（").append(record.note).append("）")
                }
                append('\n')
            }
            append("\n用 job_output(job_id=\"…\") 取输出，job_kill(job_id=\"…\") 终止。")
        }
    }
}
