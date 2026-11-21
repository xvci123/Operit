package com.ai.assistance.operit.core.tools.subagent

import android.content.Context
import com.ai.assistance.operit.core.agent.wake.TurnContext
import com.ai.assistance.operit.core.agent.wake.WakeEvent
import com.ai.assistance.operit.core.agent.wake.WakeRouter
import com.ai.assistance.operit.core.tools.MessageSendResultData
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.core.tools.defaultTool.ToolGetter
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolValidationResult
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * `spawn_subagent`：父 AI 派生一个子智能体去干活（Hermes 新增）。
 *
 * 机制参考 DSHA 的 dsh-tool-subagent，两种模式只差一个 seed：
 *  - **spawn（默认）**：子体独立会话、零历史，任务描述必须自带全部上下文
 *  - **fork**：把父会话"已完成的轮次"复制给子体（不含当前正在跑的这一轮），
 *    子体可以直接引用上面的对话，不用重新交代背景
 *
 * 两种模式其余完全一样：同样的作用域声明、同样的回报要求、同样的失败语义。
 *
 * 失败语义（照 DSHA 的 stopReason 映射改过来）：
 *  - 成功：`[子代理已返回]` + 结论
 *  - 失败：`[子代理没有完成任务]` + 分类（超时 / 空回复 / 子会话忙 / 调用失败 / 子代理报错）
 *          + 原因原文 + **已产出的部分**（DSHA 的前台调用也附半截产出，半截结论往往还有用）
 *          + 子会话 id（父体想知道细节可以自己去 get_chat_messages）
 *
 * 与 DSHA 的差异（我们更简单）：
 *  - 不做隐藏会话（v1 先让它在会话列表里可见，带 [子代理] 前缀）
 *  - 不做深度限制（用户要求不设上限）
 *  - 不做后台续跑（v1 只有一次性同步模式）
 *  - 没有 blocked 到 refusal 这一档：Operit 没有结构化 stopReason，"卡住"只能靠子体自己写清楚
 */
class SpawnSubagentToolExecutor(private val context: Context) : ToolExecutor {

    /** 后台子体的协程作用域。故意不挂在任何一轮对话上：父轮结束它还得继续跑。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "SpawnSubagent"

        const val TOOL_NAME = "spawn_subagent"
        const val DEFAULT_TIMEOUT_MS = 300_000L
        const val TITLE_PREFIX = "[子代理] "

        /** fork 时最多搬多少条历史：手机上下文经不起把整段长对话塞给子体。 */
        const val MAX_SEED_MESSAGES = 60

        /** 作用域声明：告诉子体它的权限边界，避免它反复重试被拒绝的操作。 */
        private val SCOPE_BLOCK =
                """
                [作用域声明]
                你是被委派的子智能体。你的权限在启动时就已经固定，不能自我放大。
                需要审批的操作会被自动拒绝——不要重试被拒绝的操作，
                把这个限制写进你的结果里，让父 agent 处理。
                [/作用域声明]
                """
                        .trimIndent()

        /** fork 模式：告诉子体它继承了什么（DSHA 的 fork 说法，见 dsh-tool-subagent:344-352）。 */
        private val FORK_NOTE =
                """
                [上下文继承]
                你继承了这个会话**已经完成的对话**（不含父 agent 当前正在跑的这一轮）。
                可以直接引用上面的内容，不用重新交代背景；父 agent 当前那一轮在干什么，你看不到。
                """
                        .trimIndent()

        /** 回报要求：拼在任务末尾（spawn 与 fork 只差第一句）。 */
        private fun reportBlock(forked: Boolean): String {
            val header =
                    if (forked) "你看得到历史，但父 agent 看不到你的工具输出和思考过程。"
                    else "父 agent 看不到你的对话记录、工具输出和思考过程。"
            return """
                [回报要求]
                你的父 agent 就是派你来干活的会话。完成之前，把结论整理成**自包含**的结果直接输出：
                ${header}所以不要写"见上""如前所述"，历史里的东西也要在结论里点明是哪一段。
                请给出：做了什么、结果是什么、有没有没做成的地方和原因。
                [/回报要求]
                """
                    .trimIndent()
        }
    }

    override fun invoke(tool: AITool): ToolResult {
        val task = tool.parameters.find { it.name == "task" }?.value?.trim().orEmpty()
        if (task.isEmpty()) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "task 参数必填"
            )
        }

        val label = tool.parameters.find { it.name == "description" }?.value?.trim().orEmpty()
        val fork =
                tool.parameters
                        .find { it.name == "fork" }
                        ?.value
                        ?.trim()
                        ?.lowercase()
                        .let { it == "true" || it == "1" || it == "yes" }
        val background =
                tool.parameters
                        .find { it.name == "run_in_background" }
                        ?.value
                        ?.trim()
                        ?.lowercase()
                        .let { it == "true" || it == "1" || it == "yes" }
        val timeoutMs =
                tool.parameters.find { it.name == "timeout_ms" }?.value?.toLongOrNull()
                        ?: DEFAULT_TIMEOUT_MS

        val startedAt = System.currentTimeMillis()
        val message =
                buildString {
                    appendLine(SCOPE_BLOCK)
                    appendLine()
                    if (fork) {
                        appendLine(FORK_NOTE)
                        appendLine()
                    }
                    appendLine("任务：")
                    appendLine(task)
                    appendLine()
                    append(reportBlock(fork))
                }

        return runBlocking {
            var chatId = ""
            try {
                val chatTools = ToolGetter.getChatManagerTool(context)

                // 1. 建子会话
                val created =
                        chatTools.createNewChat(
                                AITool(
                                        name = "create_new_chat",
                                        parameters =
                                                listOf(
                                                        ToolParameter(
                                                                name = "set_as_current_chat",
                                                                value = "false"
                                                        )
                                                )
                                )
                        )

                if (!created.success) {
                    return@runBlocking fail(
                            tool.name,
                            "创建子会话失败",
                            created.error ?: "未知原因",
                            "",
                            "",
                            "",
                            startedAt
                    )
                }

                chatId = extractChatId(created)
                if (chatId.isBlank()) {
                    return@runBlocking fail(
                            tool.name,
                            "创建子会话失败",
                            "创建成功但拿不到 chatId",
                            "",
                            "",
                            "",
                            startedAt
                    )
                }

                // 2. fork 时先把父会话已完成的轮次搬过去
                val seedNote = if (fork) seedChildFromParent(chatId) else ""

                // 3. 后台模式：立刻返回，跑完由唤醒路由回来找父会话
                if (background) {
                    val parentChatId = TurnContext.current()
                    val childChatIdForJob = chatId
                    val taskMessage = message
                    scope.launch {
                        runBackgroundSubagent(
                                chatTools = chatTools,
                                childChatId = childChatIdForJob,
                                parentChatId = parentChatId,
                                message = taskMessage,
                                timeoutMs = timeoutMs,
                                label = label
                        )
                    }
                    return@runBlocking ToolResult(
                            toolName = tool.name,
                            success = true,
                            result =
                                    StringResultData(
                                            renderBackgroundStarted(
                                                    label = label,
                                                    chatId = chatId,
                                                    seedNote = seedNote,
                                                    parentChatId = parentChatId
                                            )
                                    )
                    )
                }

                // 3. 下发任务（带两段固定文字）
                val sent =
                        chatTools.sendMessageToAI(
                                AITool(
                                        name = "send_message_to_ai",
                                        parameters =
                                                listOf(
                                                        ToolParameter(name = "chat_id", value = chatId),
                                                        ToolParameter(name = "message", value = message),
                                                        ToolParameter(
                                                                name = "timeout_ms",
                                                                value = timeoutMs.toString()
                                                        )
                                                )
                                )
                        )

                // 子体真正说了什么：send_message_to_ai 把文本放在 aiResponse 里。
                // 失败时（比如超时）这段往往是空的，但"已经产出的部分"仍值得带回去。
                val reply = (sent.result as? MessageSendResultData)?.aiResponse.orEmpty().trim()

                if (sent.success && reply.isNotEmpty()) {
                    ToolResult(
                            toolName = tool.name,
                            success = true,
                            result =
                                    StringResultData(
                                            renderSuccess(
                                                    label = label,
                                                    task = task,
                                                    chatId = chatId,
                                                    reply = reply,
                                                    seedNote = seedNote,
                                                    startedAt = startedAt
                                            )
                                    )
                    )
                } else {
                    fail(
                            tool.name,
                            classify(sent.error, reply),
                            sent.error?.takeIf { it.isNotBlank() }
                                    ?: if (reply.isEmpty()) "子代理没有产出任何文字" else "",
                            reply,
                            chatId,
                            seedNote,
                            startedAt
                    )
                }
            } catch (e: Exception) {
                fail(
                        tool.name,
                        "调用失败",
                        e.message ?: e.javaClass.simpleName,
                        "",
                        chatId,
                        "",
                        startedAt
                )
            }
        }
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        val task = tool.parameters.find { it.name == "task" }?.value
        return if (task.isNullOrBlank()) {
            ToolValidationResult(valid = false, errorMessage = "task 参数必填")
        } else {
            ToolValidationResult(valid = true)
        }
    }

    // ==================== 后台模式 ====================

    /** 后台子体的结算内容：一句话结论 + 结束理由 + 收尾正文（DSHA 的 createSettlementMessage）。 */
    private data class SubagentOutcome(
            val succeeded: Boolean,
            val reply: String,
            val failureClass: String
    )

    /**
     * 在后台把子体这一轮跑完，然后往父会话投一条唤醒。
     *
     * 为什么用唤醒而不是让父体轮询：父轮早就结束了，没有任何人在等这个结果；
     * 轮询要把父体卡在工具调用里（旧办法），或者让父体反复来查（费钱又慢）。
     *
     * 进程被杀时这个协程会一起消失，父会话也就收不到唤醒——子会话里留下的是半个回合。
     * 这和后台作业同一条约定：**随进程死亡，不重跑**。
     */
    private suspend fun runBackgroundSubagent(
            chatTools: com.ai.assistance.operit.core.tools.defaultTool.standard.StandardChatManagerTool,
            childChatId: String,
            parentChatId: String?,
            message: String,
            timeoutMs: Long,
            label: String
    ) {
        val outcome =
                try {
                    val sent =
                            chatTools.sendMessageToAI(
                                    AITool(
                                            name = "send_message_to_ai",
                                            parameters =
                                                    listOf(
                                                            ToolParameter(name = "chat_id", value = childChatId),
                                                            ToolParameter(name = "message", value = message),
                                                            ToolParameter(
                                                                    name = "timeout_ms",
                                                                    value = timeoutMs.toString()
                                                            )
                                                    )
                                    )
                            )
                    val reply = (sent.result as? MessageSendResultData)?.aiResponse.orEmpty().trim()
                    if (sent.success && reply.isNotEmpty()) {
                        SubagentOutcome(true, reply, "")
                    } else {
                        SubagentOutcome(false, reply, classify(sent.error, reply))
                    }
                } catch (e: Exception) {
                    SubagentOutcome(false, "", "调用失败")
                }

        if (parentChatId.isNullOrBlank()) {
            AppLogger.w(TAG, "后台子体已结束，但拿不到父会话，无法唤醒：$childChatId")
            return
        }
        try {
            WakeRouter.submit(
                    context.applicationContext,
                    WakeEvent.forSubagentReturn(
                            subChatId = childChatId,
                            parentChatId = parentChatId,
                            summary = renderSettlement(label, childChatId, outcome)
                    )
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "后台子体结算唤醒投递失败：$childChatId", e)
        }
    }

    private fun renderSettlement(
            label: String,
            childChatId: String,
            outcome: SubagentOutcome
    ): String =
            buildString {
                append("任务：").append(label.ifBlank { "（未命名）" }).append('\n')
                if (outcome.succeeded) {
                    append("状态：已完成\n")
                    append("结论：\n")
                    append(outcome.reply.take(4000))
                    if (outcome.reply.length > 4000) {
                        append("\n…（结论较长已截断，完整内容在子会话里）")
                    }
                } else {
                    append("状态：失败（").append(outcome.failureClass).append("）\n")
                    if (outcome.reply.isNotBlank()) {
                        append("已产出的部分（可能不完整）：\n")
                        append(outcome.reply.take(2000))
                    } else {
                        append("没有任何产出。")
                    }
                }
            }

    // ==================== fork 的 seed ====================

    /**
     * fork 的全部复杂度就在这一刀（DSHA 的结论：seed = 父日志最后一个 turn/end 之前的前缀）。
     *
     * 父会话里最后一条 `user` 消息就是**当前正在跑的这一轮**——它还没结束，复制过去等于让子体重放它，
     * 而且会把父体的半截结论喂给子体。所以从最后一条 user 消息切开，只搬它之前的部分。
     *
     * 拿不到父会话（不是从对话里发起的，或者已经超时）时不报错，退化成普通 spawn 并说明原因。
     */
    private suspend fun seedChildFromParent(childChatId: String): String {
        val parentChatId = TurnContext.current()
        if (parentChatId.isNullOrBlank()) {
            return "继承：跳过（拿不到父会话 id，子体按普通 spawn 处理）"
        }
        if (parentChatId == childChatId) {
            return "继承：跳过（父会话与子会话相同）"
        }
        val history =
                try {
                    ChatHistoryManager.getInstance(context).loadChatMessages(parentChatId)
                } catch (e: Exception) {
                    return "继承：失败（读取父会话出错：${e.message ?: e.javaClass.simpleName}）"
                }
        if (history.isEmpty()) {
            return "继承：跳过（父会话还没有消息）"
        }
        val cut = history.indexOfLast { it.sender == "user" }
        if (cut <= 0) {
            return "继承：跳过（父会话还没有已完成的轮次）"
        }
        val prefix = history.subList(0, cut)
        val trimmed =
                if (prefix.size > MAX_SEED_MESSAGES) prefix.subList(prefix.size - MAX_SEED_MESSAGES, prefix.size)
                else prefix
        val manager = ChatHistoryManager.getInstance(context)
        var copied = 0
        var failed = 0
        trimmed.forEach { message ->
            try {
                manager.addMessage(childChatId, message)
                copied += 1
            } catch (e: Exception) {
                failed += 1
            }
        }
        return buildString {
            append("继承：").append(copied).append(" 条父会话已完成的消息")
            if (trimmed.size < prefix.size) {
                append("（父会话太长，只取最近 ").append(MAX_SEED_MESSAGES).append(" 条）")
            }
            if (failed > 0) append("（有 ").append(failed).append(" 条复制失败）")
        }
    }

    // ==================== 结果呈现 ====================

    private fun elapsedText(startedAt: Long): String {
        val ms = System.currentTimeMillis() - startedAt
        return if (ms < 1000) "${ms}ms"
        else String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    }

    private fun renderBackgroundStarted(
            label: String,
            chatId: String,
            seedNote: String,
            parentChatId: String?
    ): String =
            buildString {
                append("[子代理已在后台开始]\n")
                append("任务：").append(label.ifBlank { "（未命名）" }).append('\n')
                if (seedNote.isNotBlank()) append(seedNote).append('\n')
                append("子会话：").append(chatId).append('\n')
                append("----\n")
                if (parentChatId.isNullOrBlank()) {
                    append("跑完**不会**叫醒你：拿不到父会话 id。想看结果就用 get_chat_messages(chat_id=\"")
                            .append(chatId)
                            .append("\") 自己取。\n")
                } else {
                    append("跑完会主动在发起它的会话里叫醒你，**不要轮询、不要 sleep 等**。\n")
                    append("要中途看进度就用 get_chat_messages(chat_id=\"").append(chatId).append("\")。\n")
                }
                append("随进程结束：应用被杀时这个子体也会中断，不会自动重跑。")
            }

    private fun renderSuccess(
            label: String,
            task: String,
            chatId: String,
            reply: String,
            seedNote: String,
            startedAt: Long
    ): String =
            buildString {
                append("[子代理已返回]\n")
                append("任务：").append(label.ifBlank { firstLine(task) }).append('\n')
                append("用时：").append(elapsedText(startedAt)).append('\n')
                if (seedNote.isNotBlank()) append(seedNote).append('\n')
                append("子会话：").append(chatId)
                append("（需要细节用 get_chat_messages(chat_id=\"").append(chatId).append("\") 去取）\n")
                append("----\n")
                append(reply)
            }

    /**
     * 失败结果。
     *
     * `reply` 非空时附在最后：DSHA 的前台调用（dsh-tool-subagent:309-318）也保留
     * "Partial output before the run ended"，因为做了一半的结论往往比一句"失败了"更有用。
     */
    private fun fail(
            toolName: String,
            status: String,
            reason: String,
            reply: String,
            chatId: String,
            seedNote: String,
            startedAt: Long
    ): ToolResult {
        val body =
                buildString {
                    append("[子代理没有完成任务]\n")
                    append("状态：").append(status).append('\n')
                    if (reason.isNotBlank()) append("原因：").append(reason).append('\n')
                    append("用时：").append(elapsedText(startedAt)).append('\n')
                    if (seedNote.isNotBlank()) append(seedNote).append('\n')
                    if (chatId.isNotBlank()) {
                        append("子会话：").append(chatId)
                        append("（它到底做了什么，用 get_chat_messages(chat_id=\"")
                                .append(chatId)
                                .append("\") 看）\n")
                    }
                    append("----\n")
                    if (reply.isNotBlank()) {
                        append("已产出的部分（可能不完整）：\n")
                        append(reply)
                    } else {
                        append("没有得到任何结论。可以换个说法重试，或者自己直接做。\n")
                    }
                }
        val headline =
                if (reason.isBlank()) "[子代理失败] $status"
                else "[子代理失败] $status：$reason"
        return ToolResult(
                toolName = toolName,
                success = false,
                result = StringResultData(body),
                error = headline
        )
    }

    /** 把 send_message_to_ai 的失败原文归类。原文见 StandardChatManagerTool.sendMessageToAI。 */
    private fun classify(error: String?, reply: String): String {
        val msg = error.orEmpty()
        return when {
            msg.isBlank() && reply.isBlank() -> "空回复"
            msg.contains("Timeout waiting for AI reply", ignoreCase = true) ->
                    "超时（子代理在时限内没有给出结论）"
            msg.contains("Timeout waiting for AI response", ignoreCase = true) ->
                    "超时（子代理根本没有开始回复）"
            msg.contains("still being processed", ignoreCase = true) -> "子会话正忙（这一轮没能开始）"
            msg.contains("Unable to get current chat ID", ignoreCase = true) -> "子会话丢失"
            msg.startsWith("Error sending message", ignoreCase = true) -> "调用失败"
            msg.isBlank() && reply.isNotBlank() -> "调用未报错但没有成功返回结论"
            else -> "子代理报错"
        }
    }

    private fun firstLine(text: String): String {
        val line = text.lineSequence().firstOrNull().orEmpty().trim()
        return if (line.length <= 40) line else line.take(40) + "…"
    }

    /** 从 create_new_chat 的结果里取出 chatId；拿不到就返回空串。 */
    private fun extractChatId(result: ToolResult): String {
        val text = result.result.toString()
        val marker = "Chat ID: "
        val index = text.indexOf(marker)
        return if (index >= 0) text.substring(index + marker.length).trim() else ""
    }
}
