package com.ai.assistance.operit.core.agent.log

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONObject
import java.io.File

/**
 * 会话账本（G.8.7.1）。
 *
 * 一句话：**每一步都记一行，只往后写，不改旧行。**
 *
 * 为什么要有它：出问题时"到底发生了什么"靠猜最费时间。有了账本，倒回去翻一遍就知道
 * 哪一轮开始的、调了哪个工具、压缩把哪段折叠了、什么时候塞进来一条通知、目标推进到第几轮。
 *
 * 三条边界（守住这几条，它才不会被自己压垮）：
 *  - **不碰数据库**：Room 实体一律不改（C3），账本是旁挂文件，Room 仍是唯一真相源。
 *  - **不给模型看**：账本是给人查的，模型看的仍然是消息与摘要。
 *  - **不重建对话**：只做"倒回去看"，不拿它当第二套存储。
 *
 * 落盘：`filesDir/hermes/log/<chatId>.jsonl`，一行一个事件，字段 `seq / ts / kind / data`。
 * seq 每次写入时读末行续号，进程被杀也不会重号。
 */
object HermesEventLog {

    private const val TAG = "HermesEventLog"
    private const val DIR = "hermes"
    private const val SUB = "log"

    /** 常用事件名。跟 DSHA 的会话事件对得上号，方便对照。 */
    const val TURN_START = "turn/start"
    const val TURN_END = "turn/end"
    const val TOOL_CALL = "tool/call"
    const val TOOL_RESULT = "tool/result"
    const val WAKE_QUEUED = "wake/queued"
    const val WAKE_INJECTED = "wake/injected"
    const val WAKE_DONE = "wake/done"
    const val COMPACTION = "compaction"
    const val GOAL_SET = "goal/set"
    const val GOAL_ROUND = "goal/round"
    const val GOAL_DONE = "goal/done"

    private fun file(context: Context, chatId: String): File {
        val dir = File(context.applicationContext.filesDir, "$DIR/$SUB")
        if (!dir.exists()) dir.mkdirs()
        val safe = chatId.replace('/', '_').replace('\\', '_').ifBlank { "unknown" }
        return File(dir, "$safe.jsonl")
    }

    /** 记一行。失败只打日志，绝不因为记账把主流程搞挂。 */
    fun record(context: Context, chatId: String?, kind: String, data: Map<String, Any?> = emptyMap()) {
        if (chatId.isNullOrBlank()) return
        try {
            val f = file(context, chatId)
            val seq = countLines(f) + 1L
            val obj = JSONObject()
            obj.put("seq", seq)
            obj.put("ts", System.currentTimeMillis())
            obj.put("kind", kind)
            val payload = JSONObject()
            data.forEach { (k, v) ->
                when (v) {
                    null -> payload.put(k, JSONObject.NULL)
                    is Number, is Boolean, is String -> payload.put(k, v)
                    else -> payload.put(k, v.toString())
                }
            }
            obj.put("data", payload)
            f.appendText(obj.toString() + "\n")
        } catch (e: Exception) {
            AppLogger.w(TAG, "记账失败（已忽略）：$kind", e)
        }
    }

    /** 读最近 n 行原文（诊断/排障用）。 */
    fun tail(context: Context, chatId: String?, n: Int = 40): String {
        if (chatId.isNullOrBlank()) return "账本：没有指定会话"
        return try {
            val f = file(context, chatId)
            if (!f.exists()) return "账本：空（这个会话还没有记账）"
            f.readLines().takeLast(n).joinToString("\n").ifBlank { "账本：空" }
        } catch (e: Exception) {
            "账本读取失败：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** 这个会话记了多少行。 */
    fun size(context: Context, chatId: String?): Long {
        if (chatId.isNullOrBlank()) return 0L
        return try {
            countLines(file(context, chatId))
        } catch (e: Exception) {
            0L
        }
    }

    /** 会话被删时把账本一起清掉。 */
    fun forget(context: Context, chatId: String) {
        try {
            val f = file(context, chatId)
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            AppLogger.w(TAG, "清账本失败", e)
        }
    }

    private fun countLines(f: File): Long {
        if (!f.exists()) return 0L
        var n = 0L
        f.forEachLine { if (it.isNotBlank()) n++ }
        return n
    }
}
