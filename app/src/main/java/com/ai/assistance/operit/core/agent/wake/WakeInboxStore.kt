package com.ai.assistance.operit.core.agent.wake

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 唤醒收件箱的落盘投影（P2）。
 *
 * 为什么不用内存队列：手机进程随时会被系统杀掉。内存里排着三条待唤醒，进程一死全丢，
 * 而用户看到的是"我说了等会儿继续，它自己忘了"。所以 inbox 是**只追加的事件日志**：
 *
 * ```
 * 所有异步源 --submit--> 追加一行 JSON --折叠--> 待派发队列
 * ```
 *
 * 折叠规则：按 `id` 去重，同一 id 后写的覆盖先写的（状态推进就是再追加一行）。这样
 * "事件不可变、状态是日志的投影"这条在手机上也能成立，而且写盘成本只有一次 append。
 *
 * 落盘位置：`filesDir/hermes/wake/inbox.jsonl`（事件）+ `state.json`（路由器的计数器与开关）。
 * 刻意不用 Room：本项目 `exportSchema=false` 且 20 段手写迁移，改实体只在运行时炸。
 */
object WakeInboxStore {

    private const val TAG = "WakeInboxStore"

    /** 日志超过这个行数就压实一次。 */
    private const val COMPACT_LINES = 500

    /** 终态事件压实后最多留这么多条，够查"刚才那次为什么没叫醒我"。 */
    private const val KEEP_FINISHED = 60

    private val lock = Any()
    private val events = LinkedHashMap<String, WakeEvent>()
    private var loaded = false
    private var lineCount = 0

    /** 路由器的运行状态（存活于进程重启之间）。 */
    data class Meta(
            val paused: Boolean = false,
            val consecutiveInternalWakes: Int = 0,
            val totalWakes: Int = 0,
            val lastUserMessageAt: Long = 0L,
            val cardWarnedAt: Long = 0L
    ) {
        fun toJson(): JSONObject =
                JSONObject().apply {
                    put("paused", paused)
                    put("consecutiveInternalWakes", consecutiveInternalWakes)
                    put("totalWakes", totalWakes)
                    put("lastUserMessageAt", lastUserMessageAt)
                    put("cardWarnedAt", cardWarnedAt)
                }

        companion object {
            fun fromJson(obj: JSONObject?): Meta {
                if (obj == null) return Meta()
                return Meta(
                        paused = obj.optBoolean("paused", false),
                        consecutiveInternalWakes = obj.optInt("consecutiveInternalWakes", 0),
                        totalWakes = obj.optInt("totalWakes", 0),
                        lastUserMessageAt = obj.optLong("lastUserMessageAt", 0L),
                        cardWarnedAt = obj.optLong("cardWarnedAt", 0L)
                )
            }
        }
    }

    @Volatile private var meta: Meta = Meta()

    private fun wakeDir(context: Context): File =
            File(File(context.filesDir, "hermes"), "wake").apply { mkdirs() }

    private fun inboxFile(context: Context): File = File(wakeDir(context), "inbox.jsonl")

    private fun stateFile(context: Context): File = File(wakeDir(context), "state.json")

    // ==================== 加载 / 压实 ====================

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val file = inboxFile(context)
            if (file.exists()) {
                try {
                    file.forEachLine { line ->
                        if (line.isNotBlank()) {
                            lineCount += 1
                            val event = parse(line)
                            if (event != null) events[event.id] = event
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "读取唤醒日志失败：${file.absolutePath}", e)
                }
            }
            try {
                val stateFile = stateFile(context)
                if (stateFile.exists()) {
                    meta = Meta.fromJson(JSONObject(stateFile.readText()))
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "读取唤醒状态失败，按默认值处理", e)
            }
            loaded = true
            AppLogger.i(TAG, "唤醒收件箱已加载：${events.size} 条事件")
        }
    }

    private fun parse(line: String): WakeEvent? =
            try {
                WakeEvent.fromJson(JSONObject(line))
            } catch (e: Exception) {
                null
            }

    /** 压实：把"正在跑的 + 最近结束的"重写成新文件，历史终态扔进 note 里的计数。 */
    private fun compact(context: Context) {
        val active = events.values.filter { it.status == WakeStatus.PENDING || it.status == WakeStatus.DISPATCHED }
        val finished = events.values.filter { it.status != WakeStatus.PENDING && it.status != WakeStatus.DISPATCHED }
                .takeLast(KEEP_FINISHED)
        val kept = LinkedHashMap<String, WakeEvent>()
        active.forEach { kept[it.id] = it }
        finished.forEach { kept[it.id] = it }
        events.clear()
        events.putAll(kept)
        rewrite(context)
    }

    private fun rewrite(context: Context) {
        try {
            val body = buildString { events.values.forEach { append(it.toJson().toString()).append('\n') } }
            inboxFile(context).writeText(body)
            lineCount = events.size
        } catch (e: Exception) {
            AppLogger.e(TAG, "重写唤醒日志失败", e)
        }
    }

    private fun appendLine(context: Context, event: WakeEvent) {
        try {
            inboxFile(context).appendText(event.toJson().toString() + "\n")
            lineCount += 1
        } catch (e: Exception) {
            AppLogger.e(TAG, "追加唤醒日志失败：${event.id}", e)
        }
        if (lineCount > COMPACT_LINES) compact(context)
    }

    // ==================== 读写 ====================

    fun put(context: Context, event: WakeEvent) {
        ensureLoaded(context)
        synchronized(lock) {
            events[event.id] = event
            appendLine(context, event)
        }
    }

    /** 按幂等键找已存在的事件（任何非 CANCELLED 状态都算"处理过"）。 */
    fun bySourceId(context: Context, sourceId: String): WakeEvent? {
        ensureLoaded(context)
        synchronized(lock) {
            return events.values.lastOrNull { it.sourceId == sourceId && it.status != WakeStatus.CANCELLED }
        }
    }

    /** 待派发队列，外部优先，其次按创建时间。 */
    fun pending(context: Context): List<WakeEvent> {
        ensureLoaded(context)
        synchronized(lock) {
            return events.values
                    .filter { it.status == WakeStatus.PENDING }
                    .sortedWith(compareByDescending<WakeEvent> { it.sourceKind.isExternal }.thenBy { it.createdAt })
                    .toList()
        }
    }

    /** 已派发但还没确认结束的事件（用来判断"这一轮跑完了没有"）。 */
    fun dispatched(context: Context): List<WakeEvent> {
        ensureLoaded(context)
        synchronized(lock) {
            return events.values.filter { it.status == WakeStatus.DISPATCHED }.toList()
        }
    }

    fun all(context: Context): List<WakeEvent> {
        ensureLoaded(context)
        synchronized(lock) {
            return events.values.toList()
        }
    }

    /** 状态推进：改内存 + 追加一行（日志即状态）。 */
    fun advance(context: Context, event: WakeEvent) {
        put(context, event)
    }

    // ==================== 路由器状态 ====================

    fun meta(context: Context): Meta {
        ensureLoaded(context)
        return meta
    }

    fun updateMeta(context: Context, newMeta: Meta) {
        ensureLoaded(context)
        synchronized(lock) {
            meta = newMeta
            try {
                stateFile(context).writeText(newMeta.toJson().toString())
            } catch (e: Exception) {
                AppLogger.w(TAG, "写唤醒状态失败", e)
            }
        }
    }

    /** 给界面/诊断看的最后 20 条（不落盘，只读）。 */
    fun render(context: Context): String {
        val list = all(context)
        if (list.isEmpty()) return "唤醒收件箱是空的。"
        return buildString {
            val m = meta(context)
            append("开关：").append(if (m.paused) "已暂停" else "正常")
            append("　连续内部唤醒：").append(m.consecutiveInternalWakes)
            append("　累计：").append(m.totalWakes).append('\n')
            val arr = JSONArray()
            list.takeLast(20).forEach { arr.put(it.toJson()) }
            append(arr.toString())
        }
    }
}
