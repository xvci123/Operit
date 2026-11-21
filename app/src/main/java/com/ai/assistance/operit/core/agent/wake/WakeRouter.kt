package com.ai.assistance.operit.core.agent.wake

import android.content.Context
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 唤醒路由器（P2）。
 *
 * 所有"该开始新一轮"的来源都走这里，只有这里能调模型。它做四件事：
 *
 *  1. **幂等**：`sourceId` 相同的事件只认一次（外部定时器重入、作业重复结算、日志重放都靠它）。
 *  2. **排队**：按外部优先、其次先来后到；当前会话正忙就等它跑完，不打断、不抢输入框。
 *  3. **落盘**：队列在 [WakeInboxStore] 里，进程被杀也不丢，下次启动接着派发。
 *  4. **预算与可见性**：连续内部唤醒计数（用户一发消息就清零）。用户要求不设硬上限，
 *     所以这里只计数、不刹车；连续第 3 次起会标记出来，界面据此插"已连续自动唤醒 N 次 · 点此暂停"。
 *
 * 明确不做的事：
 *  - 不在忙的时候把消息塞进正在跑的那一轮（那是注入，会污染当前轮的上下文），一律排队。
 *  - 不自动重试失败的命令。派发本身失败最多重试 3 次，之后标 CANCELLED 并写明原因。
 */
object WakeRouter {

    private const val TAG = "WakeRouter"

    /** 轮询间隔。唤醒不是实时业务，700ms 的延迟换来的是一份简单到不会出错的调度。 */
    private const val TICK_MS = 700L

    /** 派发出去后，多久之内必须看到这一轮真的开跑；否则认为没踢起来。 */
    private const val START_GRACE_MS = 45_000L

    /** 一轮最长认为多久结束（防"事件永远挂在 DISPATCHED"）。 */
    private const val TURN_MAX_MS = 60 * 60 * 1000L

    /** 派发最多重试几次。 */
    private const val MAX_DISPATCH_ATTEMPTS = 3

    /** 连续内部唤醒到几次开始在聊天里提示（只提示，不刹停）。 */
    const val WARN_AFTER = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val dispatching = java.util.concurrent.atomic.AtomicBoolean(false)

    fun init(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            WakeInboxStore.ensureLoaded(appContext)
            AppLogger.i(TAG, "唤醒路由器已启动")
            while (true) {
                try {
                    tick(appContext)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "唤醒调度出错（已吞掉，不影响主流程）", e)
                }
                delay(TICK_MS)
            }
        }
    }

    // ==================== 对外接口 ====================

    /** 投递结果。调用方一般只需要看 `accepted`。 */
    enum class SubmitResult {
        /** 已进队列 */
        ACCEPTED,
        /** 幂等键重复，丢弃 */
        DUPLICATE,
        /** 收件箱被暂停了（只暂停内部自唤醒） */
        PAUSED
    }

    /**
     * 投递一个唤醒事件。
     *
     * 注意：这里只负责"记下来并排队"，不会立刻开跑——开跑由调度循环在会话空闲时做。
     */
    fun submit(context: Context, event: WakeEvent): SubmitResult {
        val appContext = context.applicationContext
        init(appContext)
        val meta = WakeInboxStore.meta(appContext)

        val existing = WakeInboxStore.bySourceId(appContext, event.sourceId)
        if (existing != null) {
            AppLogger.d(TAG, "唤醒被幂等丢弃：${event.sourceId}（已有 ${existing.status}）")
            return SubmitResult.DUPLICATE
        }
        if (!event.sourceKind.isExternal && meta.paused) {
            AppLogger.i(TAG, "收件箱已暂停，内部唤醒丢弃：${event.sourceId}")
            return SubmitResult.PAUSED
        }

        val target = event.targetChatId ?: TurnContext.current()
        val normalized =
                if (target.isNullOrBlank()) event
                else event.copy(targetChatId = target)

        WakeInboxStore.put(appContext, normalized)
        AppLogger.i(
                TAG,
                "唤醒已入队：${normalized.id} ${normalized.sourceKind.name} -> ${normalized.targetChatId ?: "（未指定会话）"}"
        )
        return SubmitResult.ACCEPTED
    }

    /** 用户刚刚说话了：连续内部唤醒清零，暂停状态不影响用户自己发消息。 */
    fun userBecameActive(context: Context) {
        val appContext = context.applicationContext
        init(appContext)
        val meta = WakeInboxStore.meta(appContext)
        if (meta.consecutiveInternalWakes == 0) return
        WakeInboxStore.updateMeta(
                appContext,
                meta.copy(consecutiveInternalWakes = 0, lastUserMessageAt = System.currentTimeMillis())
        )
        AppLogger.d(TAG, "用户发言，内部唤醒计数清零")
    }

    /**
     * 某个结果已经在别处被收走了（典型：AI 自己 `job_output` 等到了作业结束）。
     * 还在排队的同源事件直接作废，避免"结果读完了又被叫醒一次"。
     */
    fun markConsumed(context: Context, sourceId: String) {
        val appContext = context.applicationContext
        init(appContext)
        val event = WakeInboxStore.bySourceId(appContext, sourceId) ?: return
        if (event.status != WakeStatus.PENDING) return
        event.status = WakeStatus.CONSUMED
        event.note = "结果已被直接取走，不必再唤醒"
        event.finishedAt = System.currentTimeMillis()
        WakeInboxStore.advance(appContext, event)
        AppLogger.i(TAG, "唤醒作废（已被 await）：$sourceId")
    }

    /** 暂停/恢复内部自唤醒。外部自动化不受影响。 */
    fun setPaused(context: Context, paused: Boolean) {
        val appContext = context.applicationContext
        init(appContext)
        WakeInboxStore.updateMeta(appContext, WakeInboxStore.meta(appContext).copy(paused = paused))
        AppLogger.i(TAG, "内部自唤醒" + if (paused) "已暂停" else "已恢复")
    }

    fun isPaused(context: Context): Boolean = WakeInboxStore.meta(context).paused

    /** 给诊断面板/工具用的一句话现状。 */
    fun stats(context: Context): String {
        val appContext = context.applicationContext
        init(appContext)
        val meta = WakeInboxStore.meta(appContext)
        val pending = WakeInboxStore.pending(appContext)
        val running = WakeInboxStore.dispatched(appContext)
        return buildString {
            append("唤醒收件箱：排队 ").append(pending.size).append(" 条，派发中 ").append(running.size).append(" 条\n")
            append("内部自唤醒：").append(if (meta.paused) "已暂停" else "正常")
            append("　连续 ").append(meta.consecutiveInternalWakes).append(" 次")
            append("　累计 ").append(meta.totalWakes).append(" 次\n")
            if (pending.isNotEmpty()) {
                append("排队中：\n")
                pending.take(10).forEach {
                    append("  ").append(it.sourceKind.cardTitle).append("  ").append(it.id).append('\n')
                }
            }
        }
    }

    // ==================== 调度 ====================

    private suspend fun tick(context: Context) {
        if (!dispatching.compareAndSet(false, true)) return
        try {
            finishSettledTurns(context)
            val next = WakeInboxStore.pending(context).firstOrNull() ?: return
            dispatch(context, next)
        } finally {
            dispatching.set(false)
        }
    }

    /** 给已派发的事件结账：这一轮跑完了就把状态推到 DONE。 */
    private suspend fun finishSettledTurns(context: Context) {
        val dispatched = WakeInboxStore.dispatched(context)
        if (dispatched.isEmpty()) return
        val core = coreOrNull(context) ?: return
        val now = System.currentTimeMillis()
        dispatched.forEach { event ->
            val chatId = event.targetChatId
            val dispatchedAt = event.dispatchedAt ?: now
            val elapsed = now - dispatchedAt
            val busy =
                    if (chatId.isNullOrBlank()) false
                    else isChatBusy(context, core, chatId)

            when {
                // 派发后一直没开跑：说明消息被丢了（会话被删/被切走），回到队列等下次
                !busy && elapsed > START_GRACE_MS && elapsed < TURN_MAX_MS -> {
                    event.attempts += 1
                    if (event.attempts >= MAX_DISPATCH_ATTEMPTS) {
                        event.status = WakeStatus.CANCELLED
                        event.note = "派发 $MAX_DISPATCH_ATTEMPTS 次都没能开始这一轮，已放弃"
                        event.finishedAt = now
                        AppLogger.w(TAG, "唤醒放弃：${event.id}（${event.note}）")
                    } else {
                        event.status = WakeStatus.PENDING
                        event.dispatchedAt = null
                        event.note = "派发后这一轮没有开始，重新排队（第 ${event.attempts} 次）"
                        AppLogger.w(TAG, "唤醒重新排队：${event.id}（${event.note}）")
                    }
                    WakeInboxStore.advance(context, event)
                }
                // 跑完了
                !busy -> {
                    event.status = WakeStatus.DONE
                    event.finishedAt = now
                    WakeInboxStore.advance(context, event)
                    AppLogger.i(TAG, "唤醒完成：${event.id}（这一轮已结束）")
                }
                // 挂太久了（比如用户就让它跑着）：记账收尾，不用一直占着"派发中"
                elapsed > TURN_MAX_MS -> {
                    event.status = WakeStatus.DONE
                    event.note = "这一轮运行超过 1 小时，按结束处理"
                    event.finishedAt = now
                    WakeInboxStore.advance(context, event)
                }
            }
        }
    }

    private suspend fun dispatch(context: Context, event: WakeEvent) {
        val targetChatId = event.targetChatId
        if (targetChatId.isNullOrBlank()) {
            event.status = WakeStatus.CANCELLED
            event.note = "没有可用的目标会话（外部触发请先确保存在会话）"
            event.finishedAt = System.currentTimeMillis()
            WakeInboxStore.advance(context, event)
            AppLogger.w(TAG, "唤醒取消：${event.id}（${event.note}）")
            return
        }
        val core = coreOrNull(context)
        if (core == null) {
            AppLogger.w(TAG, "拿不到 ChatService，本次跳过")
            return
        }
        if (isChatBusy(context, core, targetChatId)) {
            // 用户正在用这个会话：排队，等这一轮结束。这是"用户操作优先"那一条。
            return
        }

        val now = System.currentTimeMillis()
        event.attempts += 1
        event.dispatchedAt = now
        event.status = WakeStatus.DISPATCHED
        WakeInboxStore.advance(context, event)

        val meta = WakeInboxStore.meta(context)
        val newMeta =
                if (event.sourceKind.isExternal) {
                    meta.copy(totalWakes = meta.totalWakes + 1)
                } else {
                    meta.copy(
                            consecutiveInternalWakes = meta.consecutiveInternalWakes + 1,
                            totalWakes = meta.totalWakes + 1
                    )
                }
        WakeInboxStore.updateMeta(context, newMeta)
        if (!event.sourceKind.isExternal && newMeta.consecutiveInternalWakes == WARN_AFTER &&
                        meta.consecutiveInternalWakes < WARN_AFTER) {
            // 不刹停，只让它可见：界面之后据此插一张"已连续自动唤醒 N 次 · 点此暂停"
            AppLogger.w(TAG, "连续自动唤醒已达 $WARN_AFTER 次（用户要求不设上限，仅提示）")
            WakeInboxStore.updateMeta(context, newMeta.copy(cardWarnedAt = System.currentTimeMillis()))
        }

        try {
            withContext(Dispatchers.Main) {
                core.sendUserMessage(
                        chatIdOverride = targetChatId,
                        messageTextOverride = event.payload,
                        proxySenderNameOverride = "系统",
                        // 注入的消息进历史但不在界面上冒充用户说的话；卡片由 P4 单独渲染
                        turnOptions = ChatTurnOptions(hideUserMessage = true)
                )
            }
            AppLogger.i(TAG, "唤醒已派发：${event.id} -> $targetChatId")
        } catch (e: Exception) {
            event.status = WakeStatus.PENDING
            event.dispatchedAt = null
            event.note = "派发失败：" + (e.message ?: e.javaClass.simpleName)
            WakeInboxStore.advance(context, event)
            AppLogger.e(TAG, "唤醒派发失败：${event.id}", e)
        }
    }

    // ==================== 与 ChatService 的桥 ====================

    private fun coreOrNull(context: Context) =
            try {
                ChatRuntimeHolder.getInstance(context).getCore(ChatRuntimeSlot.MAIN)
            } catch (e: Exception) {
                AppLogger.w(TAG, "获取 ChatService 失败", e)
                null
            }

    /** 这个会话现在能不能接新一轮。三个条件一起看：正在流式、正在加载、当前轮有工具在跑。 */
    private fun isChatBusy(
            context: Context,
            core: com.ai.assistance.operit.services.ChatServiceCore,
            chatId: String
    ): Boolean {
        return try {
            if (core.activeStreamingChatIds.value.contains(chatId)) return true
            val processing = core.getMessageProcessingDelegate()
            if (processing.isChatLoading(chatId)) return true
            val tools = core.currentTurnToolInvocationCountByChatId.value[chatId] ?: 0
            tools > 0
        } catch (e: Exception) {
            // 判断不出来就当成忙，宁可晚一轮也不要打断用户
            true
        }
    }
}
