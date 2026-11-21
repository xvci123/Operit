package com.ai.assistance.operit.core.tools.system.shell

import com.ai.assistance.operit.util.AppLogger
import java.io.Reader

/**
 * Hermes 执行护栏（纯新增文件，不改变原有执行逻辑）。
 *
 * 解决三件事：
 *  1. 输出不设上限：`dumpsys` / `logcat` 一次几十兆，原来 `readText()` 全读进内存；
 *  2. 没有超时：Shizuku 通道 `waitFor()` 会无限期阻塞，一条命令能把整条通道占死；
 *  3. 没有句柄：进程跑出去就找不回来，想停停不了。
 *
 * 只提供常量与工具函数，具体接线由各执行器自己做（保持"增量改造"原则）。
 */
object ShellGuard {

    private const val TAG = "ShellGuard"

    /**
     * 单条命令默认超时。
     * **0 = 不限时**（用户明确决定：命令不设时间限制）。
     * 需要限时的调用方自行传值即可，执行器只在 > 0 时才挂看门狗。
     */
    const val DEFAULT_TIMEOUT_MS = 0L

    /** 单条输出流上限：4M 字符（约 4 MB 文本）。 */
    const val MAX_OUTPUT_CHARS = 4 * 1024 * 1024

    /**
     * 读取输出，超过上限立即停止并标注截断。
     *
     * 与 `readText()` 的唯一区别就是有上限，其余语义一致。
     */
    fun readCapped(reader: Reader, limit: Int = MAX_OUTPUT_CHARS): String {
        val out = StringBuilder()
        val buffer = CharArray(8192)
        var total = 0
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            if (total + read <= limit) {
                out.append(buffer, 0, read)
                total += read
            } else {
                val remaining = limit - total
                if (remaining > 0) {
                    out.append(buffer, 0, remaining)
                }
                out.append("\n\n[输出已截断：超过 ")
                out.append(limit)
                out.append(" 字符，完整内容请改用文件或终端会话获取]")
                break
            }
        }
        return out.toString()
    }

    /** 超时后的统一返回。 */
    fun timeoutResult(
            command: String,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ShellExecutor.CommandResult {
        return ShellExecutor.CommandResult(
                false,
                "",
                "命令超时（${timeoutMs / 1000} 秒）已被终止：$command",
                -1
        )
    }

    /** 尽力销毁进程；失败不抛异常。 */
    fun destroyQuietly(process: Any?) {
        if (process == null) return
        val type = process.javaClass
        try {
            type.getMethod("destroy").invoke(process)
            return
        } catch (e: Exception) {
            AppLogger.d(TAG, "destroy() unavailable: ${e.message}")
        }
        try {
            type.getMethod("close").invoke(process)
        } catch (e: Exception) {
            AppLogger.d(TAG, "close() unavailable: ${e.message}")
        }
    }
}
