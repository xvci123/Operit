package com.ai.assistance.operit.core.chat

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 压缩（上下文折叠）的 DSHA 版实现（D-12）。
 *
 * 对照 DSHA 的 `dsh-compaction-basic`，这里是它的安卓等价物：
 *
 *  - **三笔账**：`compaction/start`、`compaction/summary`、`compaction/end` 写进会话事件日志
 *    （`filesDir/hermes/events/<chatId>.jsonl`），带 `seq` 与 `compactionId`，纯日志可重放。
 *  - **锁就是日志本身**：扫日志找"最近一次没有配对 end 的 start"，存在就是 busy，直接跳过。
 *    不再用"新增够几条消息"这种土办法（那只能挡重复触发，挡不住并发）。
 *  - **失败闭合**：任何失败路径都尝试写恰好一次 `end`，并带上失败分类；
 *    写 end 本身失败就故意留着未配对的 start，下次能被检测出来。
 *  - **摘要必须更小**：产不出更短摘要 = `summary` 失败类（DSHA 同名）。
 *  - **阈值按 DSHA**：`thresholdRatio` 作用在"窗口 - 预留"上，预留取 `min(headroomTokens, ctxWin/4)`，
 *    避免 64K 窗口配 64K 预留把阈值算成 0（原来那套会变成每轮都压）。
 *
 * 仍然不碰 Room（C3）。
 */
object HermesCompaction {

    private const val TAG = "HermesCompaction"
    private const val DIR = "hermes"
    private const val EVENTS_DIR = "events"

    /** 事件名，和 DSHA 的会话事件同名。 */
    const val EV_START = "compaction/start"
    const val EV_SUMMARY = "compaction/summary"
    const val EV_END = "compaction/end"

    /** 失败分类，和 DSHA `dsh-compaction-basic` 的分类同名。 */
    enum class Failure(val code: String, val human: String) {
        COMMIT("commit", "提交不洁（这一轮还在写）"),
        CHANGED("changed", "压缩期间历史变了"),
        SUMMARY("summary", "产不出更小的摘要"),
        BUSY("busy", "已经有一次压缩在跑"),
        CANCELLED("cancelled", "被取消"),
        PERSISTENCE("persistence", "落盘失败")
    }

    /** 一次还没配对 end 的压缩。 */
    class Open(val compactionId: String, val startedAt: Long, val seq: Long)

    // ==================== 阈值与预留（DSHA 口径） ====================

    private const val HEADROOM_TOKENS = 65536L

    /** 预留：`min(headroomTokens, ctxWin/4)`。 */
    fun headroom(ctxWindowTokens: Long): Long {
        if (ctxWindowTokens <= 0L) return 0L
        return minOf(HEADROOM_TOKENS, ctxWindowTokens / 4)
    }

    /** 可用来装历史的预算 = 窗口 - 预留。 */
    fun usableTokens(ctxWindowTokens: Long): Long {
        val usable = ctxWindowTokens - headroom(ctxWindowTokens)
        return if (usable <= 0L) ctxWindowTokens else usable
    }

    /** 触发比例夹取：0.30~0.95，免得被"压完 +0.5"拨到 1.0 以上永不触发。 */
    fun clampThreshold(value: Double): Double =
            if (value.isNaN()) 0.80 else value.coerceIn(0.30, 0.95)

    /** 该不该压：用量 / 可用预算 ≥ 比例。 */
    fun shouldTrigger(usedTokens: Long, ctxWindowTokens: Long, thresholdRatio: Double): Boolean {
        val usable = usableTokens(ctxWindowTokens)
        if (usable <= 0L) return false
        val ratio = usedTokens.toDouble() / usable.toDouble()
        return ratio >= clampThreshold(thresholdRatio)
    }

    // ==================== 事件日志 ====================

    private fun eventsFile(context: Context, chatId: String): File {
        val dir = File(context.applicationContext.filesDir, "$DIR/$EVENTS_DIR")
        if (!dir.exists()) dir.mkdirs()
        val safe = chatId.replace('/', '_').replace('\\', '_').ifBlank { "unknown" }
        return File(dir, "$safe.jsonl")
    }

    private fun readEvents(context: Context, chatId: String?): List<JSONObject> {
        if (chatId.isNullOrBlank()) return emptyList()
        return try {
            val file = eventsFile(context, chatId)
            if (!file.exists()) return emptyList()
            file.readLines().mapNotNull { line ->
                if (line.isBlank()) null
                else
                        try {
                            JSONObject(line)
                        } catch (e: Exception) {
                            null
                        }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "读压缩事件失败", e)
            emptyList()
        }
    }

    private fun append(context: Context, chatId: String?, event: JSONObject) {
        if (chatId.isNullOrBlank()) return
        try {
            val file = eventsFile(context, chatId)
            val seq = readEvents(context, chatId).size.toLong() + 1L
            event.put("seq", seq)
            event.put("ts", System.currentTimeMillis())
            file.appendText(event.toString() + "\n")
        } catch (e: Exception) {
            AppLogger.w(TAG, "写压缩事件失败", e)
        }
    }

    /** 扫日志找未配对 end 的 start。 */
    fun inspectOpen(context: Context, chatId: String?): Open? {
        val events = readEvents(context, chatId)
        if (events.isEmpty()) return null
        val closed = HashSet<String>()
        events.forEach { e ->
            if (e.optString("event") == EV_END) closed.add(e.optString("compactionId"))
        }
        for (i in events.indices.reversed()) {
            val e = events[i]
            if (e.optString("event") != EV_START) continue
            val id = e.optString("compactionId")
            if (id.isNotBlank() && id !in closed) {
                return Open(id, e.optLong("ts"), e.optLong("seq"))
            }
        }
        return null
    }

    /**
     * 试着开始一次压缩。
     *
     * @return 拿到的 compactionId；null 表示已经有压缩在跑（busy），本次跳过。
     */
    fun tryStart(context: Context, chatId: String?, foldedMessages: Int, inputChars: Int): String? {
        val open = inspectOpen(context, chatId)
        if (open != null) {
            // 锁的兜底：超过 10 分钟还没 end 的 start 视为陈旧（进程被杀），不再挡路
            val age = System.currentTimeMillis() - open.startedAt
            if (age < STALE_LOCK_MS) {
                AppLogger.i(TAG, "已有压缩在跑（${open.compactionId}），本次跳过：busy")
                return null
            }
            AppLogger.w(TAG, "发现陈旧的压缩锁（${open.compactionId}，${age}ms），按 cancelled 收尾")
            append(
                    context,
                    chatId,
                    JSONObject()
                            .put("event", EV_END)
                            .put("compactionId", open.compactionId)
                            .put("failure", Failure.CANCELLED.code)
                            .put("reason", "陈旧的 start，进程可能被杀")
            )
        }
        val id = UUID.randomUUID().toString()
        append(
                context,
                chatId,
                JSONObject()
                        .put("event", EV_START)
                        .put("compactionId", id)
                        .put("foldedMessages", foldedMessages)
                        .put("inputChars", inputChars)
        )
        return id
    }

    /** 写下摘要正文（只记长度与开头，不把整篇摘要再存一遍，省空间）。 */
    fun writeSummary(context: Context, chatId: String?, compactionId: String, summaryChars: Int, head: String) {
        append(
                context,
                chatId,
                JSONObject()
                        .put("event", EV_SUMMARY)
                        .put("compactionId", compactionId)
                        .put("summaryChars", summaryChars)
                        .put("head", head.take(200))
        )
    }

    /** 收尾。failure 为空表示成功。任何路径都尝试写恰好一次 end。 */
    fun finish(context: Context, chatId: String?, compactionId: String?, failure: Failure?, detail: String = "") {
        if (compactionId.isNullOrBlank()) return
        append(
                context,
                chatId,
                JSONObject()
                        .put("event", EV_END)
                        .put("compactionId", compactionId)
                        .put("failure", failure?.code ?: "")
                        .put("reason", if (failure == null) detail else failure.human + if (detail.isBlank()) "" else "：$detail")
        )
    }

    /** 诊断/重放：把某会话的压缩事件原样读出来。 */
    fun replay(context: Context, chatId: String?, limit: Int = 50): String {
        val events = readEvents(context, chatId)
        if (events.isEmpty()) return "压缩事件：空"
        return events.takeLast(limit).joinToString("\n") { it.toString() }
    }

    /** 会话被删时清账。 */
    fun forget(context: Context, chatId: String) {
        try {
            val file = eventsFile(context, chatId)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            AppLogger.w(TAG, "清账失败", e)
        }
    }

    private const val STALE_LOCK_MS = 10 * 60 * 1000L
}
