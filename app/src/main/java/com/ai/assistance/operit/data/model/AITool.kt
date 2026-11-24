package com.ai.assistance.operit.data.model

import com.ai.assistance.operit.core.tools.ToolResultData
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Represents a tool parameter in an AI tool */
@Serializable data class ToolParameter(val name: String, val value: String)

/** Represents a tool that can be used by the AI */
@Serializable
data class AITool(
        val name: String,
        val parameters: List<ToolParameter> = emptyList(),
        val description: String = ""
)

/** Represents an invocation of a tool in the AI's response */
@Serializable
data class ToolInvocation(
        val tool: AITool,
        val rawText: String,
        @Contextual
        val responseLocation: IntRange // Where in the response this tool invocation was found
)

/**
 * 机器可读的工具错误（DSHA 对齐）。
 *
 * 模型需要的不是一句人话，而是"能不能换个参数自己重试"。因此把稳定错误码和
 * 可重试位显式带出来，而不是让模型去猜自由文本。
 */
@Serializable
data class ToolErrorInfo(
        val code: String,
        val message: String,
        val retriable: Boolean = false,
        val details: String? = null
) {
    companion object {
        const val TIMEOUT = "TIMEOUT"
        const val CANCELLED = "CANCELLED"
        const val PERMISSION_DENIED = "PERMISSION_DENIED"
        const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
        const val NOT_FOUND = "NOT_FOUND"
        const val UNAVAILABLE = "UNAVAILABLE"
        const val INTERNAL = "INTERNAL"
    }
}

/** Represents the result of a tool execution */
@Serializable
data class ToolResult(
        val toolName: String,
        val success: Boolean,
        val result: ToolResultData,
        val error: String? = null,
        /**
         * 显式的错误位。`success == false` 不等于"出错了" —— grep 没匹配、查询结果为空
         * 都是成功的"否"。执行器要报错时显式给 true，别再让上层拿 success 反推。
         */
        val isError: Boolean = !success,
        /** 结构化错误；有它时消息里会带 code 与 retriable，模型才知道该不该重试。 */
        val errorInfo: ToolErrorInfo? = null,
        /**
         * 不进 result body、但要进上下文的补充信息（环境变化、后续提示、引用片段）。
         * 和正文分开是为了不被 [com.ai.assistance.operit.core.tools.ToolExecutionLimits]
         * 的正文截断吃掉。
         */
        val additionalContexts: List<String> = emptyList(),
        /** 这一轮到此结束：工具自己知道后面没事可做了，不必再让模型多跑一跳。 */
        val concludesTurn: Boolean = false
)

/** Represents the validation result for tool parameters */
@Serializable data class ToolValidationResult(val valid: Boolean, val errorMessage: String = "")
