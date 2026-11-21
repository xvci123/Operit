package com.ai.assistance.operit.core.tools.subagent

import android.content.Context
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
 * 机制参考 DSHA 的 dsh-subagent：
 *  - 子体是一个**独立会话**，不继承父的对话，只拿到任务描述
 *  - 任务消息里附带两段固定文字：作用域声明 + 回报要求
 *  - 干完一轮后，结果作为**本次工具调用的返回值**交回父 AI
 *
 * 与 DSHA 的差异（我们更简单）：
 *  - 不做隐藏会话（v1 先让它在会话列表里可见，带 [子代理] 前缀）
 *  - 不做深度限制（用户要求不设上限）
 *  - 不做后台续跑（v1 只有一次性模式）
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

        val description = tool.parameters.find { it.name == "description" }?.value?.trim()
        val timeoutMs =
                tool.parameters.find { it.name == "timeout_ms" }?.value?.toLongOrNull()
                        ?: DEFAULT_TIMEOUT_MS

        return runBlocking {
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
                    return@runBlocking ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error = "创建子会话失败：${created.error ?: "未知原因"}"
                    )
                }

                val chatId = extractChatId(created)
                if (chatId.isBlank()) {
                    return@runBlocking ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error = "创建子会话成功但拿不到 chatId"
                    )
                }

                // 2. 下发任务（带两段固定文字）
                val message =
                        buildString {
                            appendLine(SCOPE_BLOCK)
                            appendLine()
                            appendLine("任务：")
                            appendLine(task)
                            appendLine()
                            append(REPORT_BLOCK)
                        }

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

                ToolResult(
                        toolName = tool.name,
                        success = sent.success,
                        result = sent.result,
                        error = sent.error
                )
            } catch (e: Exception) {
                ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "派发子智能体失败：${e.message}"
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

    /** 从 create_new_chat 的结果里取出 chatId；拿不到就返回空串。 */
    private fun extractChatId(result: ToolResult): String {
        val text = result.result.toString()
        val marker = "Chat ID: "
        val index = text.indexOf(marker)
        return if (index >= 0) text.substring(index + marker.length).trim() else ""
    }
}
