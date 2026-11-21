package com.ai.assistance.operit.core.tools.ask

import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.StringResultData
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolValidationResult
import kotlinx.coroutines.runBlocking

/**
 * `ask_user`：模型主动向用户提一个问题并等待回答（Hermes 新增）。
 *
 * 与"工具权限确认"完全不同：
 *  - 权限确认是系统闸门，只能允许/拒绝，阻塞工具执行
 *  - ask_user 是模型的一次提问，用户可以回答任意内容，回答后模型继续推理
 *
 * 返回五态：ANSWER / TIMEOUT / DISMISSED / BACKGROUND / STOPPED
 * 超时不会自动挑默认选项。
 */
class AskUserToolExecutor : ToolExecutor {

    companion object {
        const val TOOL_NAME = "ask_user"
        const val DEFAULT_TIMEOUT_MS = 120_000L
        const val MAX_OPTIONS = 3
    }

    override fun invoke(tool: AITool): ToolResult {
        val question = tool.parameters.find { it.name == "question" }?.value?.trim().orEmpty()
        if (question.isEmpty()) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "question 不能为空"
            )
        }

        val options =
                (tool.parameters.find { it.name == "options" }?.value ?: "")
                        .split("|")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .take(MAX_OPTIONS)

        val timeoutMs =
                tool.parameters.find { it.name == "timeout_ms" }?.value?.toLongOrNull()
                        ?: DEFAULT_TIMEOUT_MS

        val (outcome, answer) = runBlocking { AskUserBridge.ask(question, options, timeoutMs) }

        val text =
                when (outcome) {
                    AskOutcome.ANSWER -> "ANSWER: ${answer.orEmpty()}"
                    AskOutcome.TIMEOUT ->
                            "TIMEOUT: 用户未在 ${timeoutMs / 1000} 秒内作答，请据此决定下一步，不要假设用户同意"
                    AskOutcome.DISMISSED -> "DISMISSED: 用户关闭了这个问题，没有作答"
                    AskOutcome.BACKGROUND -> "BACKGROUND: 应用不在前台，无法向用户提问"
                    AskOutcome.STOPPED -> "STOPPED: 对话已被中断"
                }

        return ToolResult(
                toolName = tool.name,
                success = outcome == AskOutcome.ANSWER,
                result = StringResultData(text)
        )
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        val question = tool.parameters.find { it.name == "question" }?.value
        return if (question.isNullOrBlank()) {
            ToolValidationResult(valid = false, errorMessage = "question 参数必填")
        } else {
            ToolValidationResult(valid = true)
        }
    }
}
