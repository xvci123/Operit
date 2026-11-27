package com.ai.assistance.operit.core.agent.wake

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.AppLogger

/**
 * 转向槽（D-09 的 busy 分支）。
 *
 * 为什么需要它：Operit 在会话忙的时候会**直接丢弃**新的用户消息
 * （`MessageProcessingDelegate.sendUserMessage` 里 `isLoading` 就 return），
 * 所以"后台活儿干完了"这种消息如果只是排队，用户得等这一轮彻底跑完才看得到，
 * 而 DSHA 的语义是 **busy → inject**：把这段话塞进当前这一轮的"下一步"，
 * 也就是「工具结果已经回来、下一次请求模型之前」那个缝里，让模型在下一步自然看到它，
 * 既不打断当前步，也不用多开一轮。
 *
 * 落点：`EnhancedAIService.processToolResults()` 组装工具结果消息的地方
 * （见那边对 [drainTurns] 的调用）。
 *
 * 约束：
 *  - 只在**这一轮确实有工具在跑**时才允许注入。否则说明这一轮正卡在最后一次模型请求里，
 *    缝已经过去了，注入进去没人接，事件会永远挂着——这种情况仍然走排队派发。
 *  - 同一个 `sourceId` 只排一次，重复 push 返回 false（磁盘上的唤醒事件会一直重试到被取走）。
 *  - 内存态，进程死了就丢；但磁盘上的 [WakeEvent] 仍是 PENDING，重启后会重新 push，
 *    所以不会丢消息，最多丢一次"已注入但没等到模型回复"的机会。
 */
object HermesSteer {

    private const val TAG = "HermesSteer"

    /** 一段待注入的话。 */
    class Pending(val sourceId: String, val title: String, val text: String) {
        var injected: Boolean = false
    }

    private val lock = Any()
    private val queues = HashMap<String, MutableList<Pending>>()

    /**
     * 排一段话进这个会话的"下一步"。
     *
     * @return true 表示这次真的排进去了；false 表示已经排过（幂等）。
     */
    fun push(chatId: String, sourceId: String, title: String, text: String): Boolean {
        if (chatId.isBlank() || text.isBlank()) return false
        synchronized(lock) {
            val queue = queues.getOrPut(chatId) { mutableListOf() }
            if (queue.any { it.sourceId == sourceId }) return false
            // 同一个会话排太多会把这轮上下文顶爆，超过 8 条就只留最早的，其余丢弃并记账
            if (queue.size >= MAX_PENDING) {
                AppLogger.w(TAG, "转向槽已满（$MAX_PENDING），丢弃：$sourceId")
                return false
            }
            queue.add(Pending(sourceId, title, text))
            AppLogger.i(TAG, "已排进下一步：$sourceId（会话 $chatId 现有 ${queue.size} 条）")
            return true
        }
    }

    /** 这个会话有没有待注入的话（不看内容）。 */
    fun hasPending(chatId: String): Boolean = synchronized(lock) {
        queues[chatId]?.isNotEmpty() == true
    }

    /**
     * 取走并转成**独立的一条消息**（D-09 的 inject 语义，按 DSHA 原版形态）。
     *
     * 早先的版本是把这段话拼进工具输出的正文，模型看到的是"命令输出里夹了一句备注"；
     * DSHA 是单独发一条注入消息进 transcript，模型看到的是一条独立通知。
     * 这里返回 `PromptTurn(kind = USER)`，由调用方紧跟在工具结果后面塞进本轮上下文。
     *
     * 取走后同步把磁盘上的唤醒事件标成已派发，避免同一个结果既注入又再叫醒一次。
     */
    fun drainTurns(context: Context, chatId: String?): List<PromptTurn> {
        if (chatId.isNullOrBlank()) return emptyList()
        val taken: List<Pending> =
                synchronized(lock) {
                    val queue = queues[chatId]
                    if (queue == null || queue.isEmpty()) return emptyList()
                    val copy = queue.toList()
                    queue.clear()
                    copy
                }
        taken.forEach { p ->
            p.injected = true
            try {
                WakeRouter.markInjected(context, p.sourceId)
            } catch (e: Exception) {
                AppLogger.w(TAG, "标记已注入失败：${p.sourceId}", e)
            }
        }
        AppLogger.i(TAG, "注入 ${taken.size} 条到下一步（会话 $chatId）")
        return taken.map { p ->
            PromptTurn(
                    kind = PromptTurnKind.USER,
                    content =
                            buildString {
                                append(p.title)
                                append('\n')
                                append(p.text.trim())
                                append('\n')
                                append("（这是运行期间新到达的通知，不是用户的回复。请先确认它是否需要你改变当前做法；")
                                append("不需要就不要重复已经做过的事，也不要为它道歉。）")
                            }
            )
        }
    }

    /** 诊断用：当前各会话积压条数。 */
    fun stats(): String = synchronized(lock) {
        if (queues.isEmpty()) "转向槽：空"
        else
                "转向槽：" +
                        queues.entries
                                .filter { it.value.isNotEmpty() }
                                .joinToString("，") { "${it.key}=${it.value.size}" }
                                .ifBlank { "空" }
    }

    /** 会话被删/被清空时把它的槽清掉。 */
    fun clear(chatId: String) {
        synchronized(lock) { queues.remove(chatId) }
    }

    /** 单个会话最多积压多少条。 */
    private const val MAX_PENDING = 8
}
