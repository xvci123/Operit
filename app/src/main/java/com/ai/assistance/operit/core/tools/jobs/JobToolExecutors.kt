package com.ai.assistance.operit.core.tools.jobs

import android.content.Context
import com.ai.assistance.operit.core.agent.jobs.JobManager
import com.ai.assistance.operit.core.agent.wake.TurnContext
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.core.tools.system.shell.CommandSafety
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/*
 * Hermes 后台作业三件套：run_in_background / job_output / job_kill。
 *
 * 三个工具都是薄壳，真正的状态与落盘在 JobManager。
 *
 * 为什么 run_in_background 不自己跑命令：作业必须与前台 execute_shell 走同一条通道，
 * 否则 root / Shizuku 的授权与严格模式判定会被绕过。这里只把命令交给 JobManager，
 * 由它调用 AndroidShellExecutor.startShellProcess。
 */

/** 启动一个后台作业。命令不设时间上限，作业随应用进程一起结束。 */
class RunInBackgroundToolExecutor(private val context: Context) : ToolExecutor {

    companion object {
        const val TOOL_NAME = "run_in_background"
    }

    override fun invoke(tool: AITool): ToolResult {
        val validation = validateParameters(tool)
        if (!validation.valid) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = validation.errorMessage
            )
        }

        val command = tool.parameters.find { it.name == "command" }?.value?.trim().orEmpty()
        val title = tool.parameters.find { it.name == "title" }?.value

        return try {
            // owner 取自"最近真正开跑的会话"：作业结束时唤醒要投回它。
            val record = JobManager.start(context, command, title, TurnContext.current())
            val text =
                    buildString {
                        append("作业已启动：").append(record.id).append('\n')
                        append("状态：RUNNING（运行中）\n")
                        append("标题：").append(record.title).append('\n')
                        append("命令：").append(record.command).append('\n')
                        append("不限时：命令没有超时上限，会一直跑到自己结束或被 job_kill 终止。\n")
                        append("随进程结束：应用进程被杀时作业会中断，不会自动重跑。\n")
                        append("下一步：用 job_output(job_id=\"").append(record.id).append("\") 取输出；")
                        append("需要停掉就 job_kill(job_id=\"").append(record.id).append("\")。")
                    }
            ToolResult(toolName = tool.name, success = true, result = StringResultData(text))
        } catch (e: Exception) {
            ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "启动后台作业失败：${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        val command = tool.parameters.find { it.name == "command" }?.value
        if (command.isNullOrBlank()) {
            return ToolValidationResult(valid = false, errorMessage = "command 参数必填")
        }
        val reason = CommandSafety.catastrophicReason(command)
        if (reason != null) {
            // 与 AIToolHandler.checkToolInterception 用同一判定与同一措辞，两处任一命中都拒绝。
            return ToolValidationResult(
                    valid = false,
                    errorMessage = "[策略拒绝] 该命令被判定为不可逆操作：$reason"
            )
        }
        return ToolValidationResult(valid = true)
    }
}

/** 读取作业输出；带 wait 时会等作业结束（最多 timeout_ms）。不给 job_id 则列出全部作业。 */
class JobOutputToolExecutor(private val context: Context) : ToolExecutor {

    companion object {
        const val TOOL_NAME = "job_output"
    }

    override fun invoke(tool: AITool): ToolResult {
        val jobId = tool.parameters.find { it.name == "job_id" }?.value?.trim().orEmpty()
        if (jobId.isEmpty()) {
            return ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(JobManager.list(context))
            )
        }

        val waitFlag = tool.parameters.find { it.name == "wait" }?.value?.trim()?.lowercase()
        val waitRequested =
                waitFlag == "true" || waitFlag == "1" || waitFlag == "yes" || waitFlag == "y"
        val timeoutMs =
                tool.parameters.find { it.name == "timeout_ms" }?.value?.toLongOrNull()
                        ?: JobManager.DEFAULT_WAIT_MS
        val maxChars =
                tool.parameters.find { it.name == "max_chars" }?.value?.toIntOrNull()
                        ?: JobManager.DEFAULT_MAX_CHARS
        val fromHead =
                tool.parameters.find { it.name == "mode" }?.value?.trim()?.lowercase() == "head"
        // 增量读：传了 since_offset 就只取该偏移之后的一段（上一次回复末尾会告诉你该传多少）
        val sinceOffset =
                tool.parameters.find { it.name == "since_offset" }?.value?.trim()?.toIntOrNull()

        val text =
                runBlocking(Dispatchers.IO) {
                    JobManager.read(
                            context = context,
                            id = jobId,
                            waitMs = if (waitRequested) timeoutMs else 0L,
                            maxChars = maxChars,
                            fromHead = fromHead,
                            sinceOffset = sinceOffset
                    )
                }
        return ToolResult(toolName = tool.name, success = true, result = StringResultData(text))
    }
}

/** 终止一个后台作业。 */
class JobKillToolExecutor(private val context: Context) : ToolExecutor {

    companion object {
        const val TOOL_NAME = "job_kill"
    }

    override fun invoke(tool: AITool): ToolResult {
        val jobId = tool.parameters.find { it.name == "job_id" }?.value?.trim().orEmpty()
        if (jobId.isEmpty()) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "job_id 参数必填"
            )
        }
        val reason = tool.parameters.find { it.name == "reason" }?.value
        val text = JobManager.kill(context, jobId, reason)
        return ToolResult(toolName = tool.name, success = true, result = StringResultData(text))
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        val jobId = tool.parameters.find { it.name == "job_id" }?.value
        return if (jobId.isNullOrBlank()) {
            ToolValidationResult(valid = false, errorMessage = "job_id 参数必填")
        } else {
            ToolValidationResult(valid = true)
        }
    }
}
