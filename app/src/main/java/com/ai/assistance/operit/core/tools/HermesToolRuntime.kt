package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.data.model.ToolResult
import java.io.File

/**
 * P-B 工具运行时策略（DSHA ToolDescriptor 的 Kotlin 对应物）。
 *
 * 原则：
 *  - 默认 **串行、无超时**。特别是 shell / 设备命令，遵守“命令不设时间限制”，不能套全局 deadline。
 *  - 只有明确声明可并发的只读工具才并行。
 *  - 只有能安全取消的读取/网络类工具才设置 deadline；超时物化为 ToolResult，不抛到会话层。
 *  - 输出统一在模型边界截断，保留头尾；中间用明确标记提示用分段工具读取。
 */
object HermesToolRuntime {

    data class Policy(
        val concurrencySafe: Boolean = false,
        /** null = 不设 deadline。 */
        val timeoutMs: Long? = null,
        /** 单次进模型的字符上限。 */
        val maxResultChars: Int = DEFAULT_MAX_RESULT_CHARS
    )

    const val DEFAULT_MAX_RESULT_CHARS = 48_000
    private const val HEAD_CHARS = 28_000
    private const val TAIL_CHARS = 16_000

    private val policies: Map<String, Policy> =
        buildMap {
            // 只读、无共享可变状态，可以并行。
            listOf(
                "list_files", "read_file", "read_file_part", "read_file_full",
                "file_exists", "find_files", "file_info", "grep_code",
                "calculate", "ffmpeg_info"
            ).forEach { this[it] = Policy(concurrencySafe = true, timeoutMs = 45_000) }

            // 下载会写文件，可能很大：串行、不设 deadline。
            this["download_file"] = Policy(timeoutMs = null)

            // 网络读取可取消，但给更宽的 deadline。
            this["visit_web"] = Policy(concurrencySafe = true, timeoutMs = 90_000)
            this["web_search"] = Policy(concurrencySafe = true, timeoutMs = 90_000)

            // 用户提问和后台作业由自身超时/等待语义控制，外层不重复套。
            this["ask_user"] = Policy(timeoutMs = null, maxResultChars = 8_000)
            this["job_output"] = Policy(timeoutMs = null)
            this["spawn_subagent"] = Policy(timeoutMs = null)

            // 明确不设超时：shell、设备操作、后台作业启动、终止。
            listOf(
                "execute_shell", "shell", "run_in_background", "job_kill",
                "use_package", "apply_patch", "write_file", "delete_file"
            ).forEach { this[it] = Policy(timeoutMs = null) }
        }

    fun policyFor(toolName: String): Policy {
        // 包工具名形如 pkg:tool，先查全名，再查叶子名。
        return policies[toolName]
            ?: policies[toolName.substringAfter(':', toolName)]
            ?: Policy()
    }

    fun isConcurrencySafe(toolName: String): Boolean = policyFor(toolName).concurrencySafe

    fun deadlineMs(toolName: String): Long? = policyFor(toolName).timeoutMs

    fun maxResultChars(toolName: String): Int = policyFor(toolName).maxResultChars

    /**
     * 模型边界截断。保留头尾，中间给出可操作的提示，而不是静默截掉。
     * 注意：这是“进模型”的边界，完整结果仍由各工具自己落盘/分段。
     */
    fun limitForModel(toolName: String, text: String): String {
        val max = maxResultChars(toolName)
        if (text.length <= max) return text
        val head = text.take(HEAD_CHARS.coerceAtMost(max / 2))
        val tail = text.takeLast(TAIL_CHARS.coerceAtMost(max / 3))
        val omitted = text.length - head.length - tail.length
        return buildString {
            append(head)
            append("\n\n[hermes: output truncated, omitted ")
            append(omitted)
            append(" chars; use a paged/read tool for the omitted middle]\n\n")
            append(tail)
        }
    }

    /**
     * 大结果全文落盘到 cache/hermes/tool-spill，再把路径交给模型。
     * 失败时退化为纯截断，不能让 spill 本身把工具结果变成错误。
     */
    fun limitForModelWithSpill(
        context: Context,
        toolName: String,
        text: String
    ): String {
        if (text.length <= maxResultChars(toolName)) return text
        val path = runCatching {
            val dir = File(context.cacheDir, "hermes/tool-spill").apply { mkdirs() }
            val safeName = toolName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
            val file = File(dir, "${System.currentTimeMillis()}-$safeName.txt")
            file.writeText(text)
            file.absolutePath
        }.getOrNull()
        val limited = limitForModel(toolName, text)
        return if (path == null) limited
        else "$limited\n\n[full output: $path]"
    }

    fun timeoutResult(toolName: String, timeoutMs: Long): ToolResult {
        return ToolResult(
            toolName = toolName,
            success = false,
            result = StringResultData(""),
            error = "TIMEOUT: tool timed out after ${timeoutMs}ms (HermesToolRuntime)"
        )
    }
}
