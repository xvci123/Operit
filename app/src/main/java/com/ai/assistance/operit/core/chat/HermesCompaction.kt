package com.ai.assistance.operit.core.chat

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONObject
import java.io.File

/**
 * 压缩守卫 + 压缩流水账（阶段 6）。
 *
 * 解决 Operit 原生总结的三个真问题：
 *
 * 1. **区间重叠**：消息条数触发和 token 比例触发互相不知道对方干过活。
 *    刚压完，条数仍然超阈值（因为"自上次总结后"的计数口径和插入位置不一致），
 *    于是立刻压第二次，把刚保留的最近几轮又吃进去。这里记住"上次压完时历史有多少条"，
 *    新增消息不够就不许再压（除非 token 已经明显超过阈值，那是真紧急）。
 * 2. **越压越长**：原生 prompt 明写"不要限制字数"，模型会把上一版摘要原样扩写，
 *    压完反而更大。这里在压缩前后各量一次字数，压完不更小就拒收（见 summarizeHistory）。
 * 3. **压了什么没人知道**：每次压缩写一行流水账（`filesDir/hermes/compaction.jsonl`），
 *    记录折叠了哪一段、多少字、压成多少字。出问题能倒回去看，也是将来"压缩事件化"的底子。
 *
 * 只用 SharedPreferences + 追加写文件，不碰 Room 实体（C3 硬约束）。
 */
object HermesCompaction {

    private const val TAG = "HermesCompaction"
    private const val PREFS = "hermes_compaction"
    private const val KEY_PREFIX = "last_count_"
    private const val LOG_DIR = "hermes"
    private const val LOG_FILE = "compaction.jsonl"

    /** 压完之后，至少要有这么多条新消息才允许再压一次。 */
    private const val MIN_NEW_MESSAGES = 6

    /** 超过阈值这么多（比例）时，无视上面的条数下限，紧急压缩。 */
    private const val EMERGENCY_MARGIN = 0.15

    /** 阈值上限：原来压缩后会 `+0.5` 拨高阈值，容易拨过 1.0 变成永不触发，这里压住。 */
    private const val MAX_THRESHOLD = 0.95
    private const val MIN_THRESHOLD = 0.30

    fun clampThreshold(value: Double): Double =
            if (value.isNaN()) MAX_THRESHOLD else value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 上次压完时这个会话有多少条历史。 */
    fun lastCount(context: Context, chatId: String?): Int {
        if (chatId.isNullOrBlank()) return 0
        return try {
            prefs(context).getInt(KEY_PREFIX + chatId, 0)
        } catch (e: Exception) {
            0
        }
    }

    /** 记下"压完了，历史这么多条"。 */
    fun mark(context: Context, chatId: String?, messageCount: Int) {
        if (chatId.isNullOrBlank()) return
        try {
            prefs(context).edit().putInt(KEY_PREFIX + chatId, messageCount).apply()
        } catch (e: Exception) {
            AppLogger.w(TAG, "记账失败", e)
        }
    }

    /** 会话被删时清账。 */
    fun forget(context: Context, chatId: String) {
        try {
            prefs(context).edit().remove(KEY_PREFIX + chatId).apply()
        } catch (e: Exception) {
            AppLogger.w(TAG, "清账失败", e)
        }
    }

    /**
     * 这次该不该压。
     *
     * @param messageCount 当前历史条数
     * @param usageRatio 当前上下文占用比例（0~1+），拿不到就传 0
     * @param threshold 配置的阈值
     */
    fun allow(
            context: Context,
            chatId: String?,
            messageCount: Int,
            usageRatio: Double,
            threshold: Double
    ): Boolean {
        val last = lastCount(context, chatId)
        if (last <= 0) return true
        val growth = messageCount - last
        // 上一次压缩之后历史反而变短（被截断/被删）→ 重新记账，不拦
        if (growth < 0) {
            mark(context, chatId, messageCount)
            return true
        }
        if (growth >= MIN_NEW_MESSAGES) return true
        if (threshold > 0 && usageRatio >= clampThreshold(threshold + EMERGENCY_MARGIN)) {
            AppLogger.w(
                    TAG,
                    "距上次压缩只多了 $growth 条，但上下文已用到 ${(usageRatio * 100).toInt()}%，紧急压缩"
            )
            return true
        }
        AppLogger.d(TAG, "距上次压缩只多了 $growth 条（下限 $MIN_NEW_MESSAGES），本次不压")
        return false
    }

    /** 写一行压缩流水账。 */
    fun logCompaction(
            context: Context,
            chatId: String?,
            foldedMessages: Int,
            inputChars: Int,
            summaryChars: Int,
            accepted: Boolean,
            reason: String
    ) {
        try {
            val dir = File(context.applicationContext.filesDir, LOG_DIR)
            if (!dir.exists()) dir.mkdirs()
            val line =
                    JSONObject().apply {
                        put("ts", System.currentTimeMillis())
                        put("chatId", chatId ?: "")
                        put("folded", foldedMessages)
                        put("inputChars", inputChars)
                        put("summaryChars", summaryChars)
                        put("accepted", accepted)
                        put("reason", reason)
                    }
            File(dir, LOG_FILE).appendText(line.toString() + "\n")
        } catch (e: Exception) {
            AppLogger.w(TAG, "流水账写入失败", e)
        }
    }

    /** 诊断用：最近几次压缩。 */
    fun recent(context: Context, limit: Int = 10): String {
        return try {
            val file = File(File(context.applicationContext.filesDir, LOG_DIR), LOG_FILE)
            if (!file.exists()) return "压缩流水账：空"
            file.readLines().takeLast(limit).joinToString("\n")
        } catch (e: Exception) {
            "压缩流水账读取失败：" + (e.message ?: "")
        }
    }
}
