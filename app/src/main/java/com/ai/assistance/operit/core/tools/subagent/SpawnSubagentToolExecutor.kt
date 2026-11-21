package com.ai.assistance.operit.core.tools.subagent

import android.content.Context
import com.ai.assistance.operit.core.tools.MessageSendResultData
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.core.tools.defaultTool.ToolGetter
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolValidationResult
import kotlinx.coroutines.runBlocking

/**
 * `spawn_subagent`：父 AI 派生一个子智能体去干活（Hermes 新增）。
 *
 * 机制参考 DSHA 的 dsh-tool-subagent：
 *  - 子体是一个**独立会话**，不继承父的对话，只拿到任务描述
 *  - 任务消息里附带两段固定文字：作用域声明 + 回报要求
 *  - 干完一轮后，结果作为**本次工具调用的返回值**交回父 AI
 *  - 父体只拿到**最终结论**，拿不到子体的工具输出与思考过程
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

    companion object {
        const val TOOL_NAME = "spawn_subagent"
        const val DEFAULT_TIMEOUT_MS = 300_000L
        const val TITLE_PREFIX = "[子代理] "

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

        /** 回报要求：拼在任务末尾。 */
        private val REPORT_BLOCK =
                """
                [回报要求]
                你的父 agent 就是派你来干活的会话。完成之前，把结论整理成**自包含**的结果直接输出：
                父 agent 看不到你的对话记录、工具输出和思考过程，所以不要写"见上""如前所述"。
                请给出：做了什么、结果是什么、有没有没做成的地方和原因。
                [/回报要求]
                """
                        .trimIndent()
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
        val timeoutMs =
                tool.parameters.find { it.name == "timeout_ms" }?.value?.toLongOrNull()
                        ?: DEFAULT_TIMEOUT_MS

        val startedAt = System.currentTimeMillis()
        val message =
                buildString {
                    appendLine(SCOPE_BLOCK)
                    appendLine()
                    appendLine("任务：")
                    appendLine(task)
                    appendLine()
                    append(REPORT_BLOCK)
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
                            startedAt
                    )
                }

                // 2. 下发任务（带两段固定文字）
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
                            result = StringResultData(renderSuccess(label, task, chatId, reply, startedAt))
                    )
                } else {
                    fail(
                            tool.name,
                            classify(sent.error, reply),
                            sent.error?.takeIf { it.isNotBlank() }
                                    ?: if (reply.isEmpty()) "子代理没有产出任何文字" else "",
                            reply,
                            chatId,
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

    // ==================== 结果呈现 ====================

    private fun elapsedText(startedAt: Long): String {
        val ms = System.currentTimeMillis() - startedAt
        return if (ms < 1000) "${ms}ms"
        else String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    }

    private fun renderSuccess(
            label: String,
            task: String,
            chatId: String,
            reply: String,
            startedAt: Long
    ): String =
            buildString {
                append("[子代理已返回]\n")
                append("任务：").append(label.ifBlank { firstLine(task) }).append('\n')
                append("用时：").append(elapsedText(startedAt)).append('\n')
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
            startedAt: Long
    ): ToolResult {
        val body =
                buildString {
                    append("[子代理没有完成任务]\n")
                    append("状态：").append(status).append('\n')
                    if (reason.isNotBlank()) append("原因：").append(reason).append('\n')
                    append("用时：").append(elapsedText(startedAt)).append('\n')
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
