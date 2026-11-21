package com.ai.assistance.operit.core.tools.system.shell

/**
 * Hermes 命令判定层（替换原来的 `contains("rm -rf") || contains("format")`）。
 *
 * 设计原则：**默认放行**。只有「不可逆且会造成整机/整盘损坏」的命令才会被识别出来，
 * 其余命令（包括 `--format json` 这类正常参数、`format` 作为普通字符串出现在路径或参数里）
 * 一律放行。
 *
 * 能识别、而旧实现识别不了的写法：
 *  - `rm -fr` / `rm -r -f` / `rm --recursive --force` / `rm -Rf`
 *  - `find / -delete`
 *  - `dd of=/dev/block/...`、`> /dev/block/...`
 *  - `mkfs*`、`wipefs`、`fdisk`、`sgdisk`、`parted`
 *
 * 只做判定，不做 I/O，纯函数，便于单测。
 */
object CommandSafety {

    /** 一旦被递归删除就等同于抹机的路径。 */
    private val protectedPaths =
            setOf(
                    "/",
                    "/*",
                    "/system",
                    "/system/*",
                    "/data",
                    "/data/*",
                    "/vendor",
                    "/vendor/*",
                    "/product",
                    "/product/*",
                    "/boot",
                    "/dev"
            )

    /**
     * 返回 null 表示放行；返回非空字符串表示这是不可逆命令，字符串是给人看的原因。
     */
    fun catastrophicReason(command: String?): String? {
        if (command.isNullOrBlank()) return null
        for (segment in splitSegments(command)) {
            val tokens = tokenize(segment)
            if (tokens.isEmpty()) continue
            val reason = inspectSegment(tokens)
            if (reason != null) return reason
        }
        return null
    }

    /** 命令里是否含不可逆操作。 */
    fun isCatastrophic(command: String?): Boolean = catastrophicReason(command) != null

    private fun splitSegments(command: String): List<String> {
        return command.replace("&&", "\n")
                .replace("||", "\n")
                .replace(";", "\n")
                .replace("|", "\n")
                .split("\n")
    }

    /** 极简分词：支持单双引号，不处理转义（够用且不会抛异常）。 */
    private fun tokenize(segment: String): List<String> {
        val out = ArrayList<String>()
        val buffer = StringBuilder()
        var quote = ' '
        for (ch in segment) {
            when {
                quote != ' ' -> {
                    if (ch == quote) quote = ' ' else buffer.append(ch)
                }
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> {
                    if (buffer.isNotEmpty()) {
                        out.add(buffer.toString())
                        buffer.setLength(0)
                    }
                }
                else -> buffer.append(ch)
            }
        }
        if (buffer.isNotEmpty()) out.add(buffer.toString())
        return out
    }

    private fun inspectSegment(tokens: List<String>): String? {
        var index = 0
        // 处理前置重定向：> /dev/block/x
        while (index < tokens.size) {
            val token = tokens[index]
            if (token == ">" || token == ">>" || token == "<") {
                val target = tokens.getOrNull(index + 1) ?: return null
                if (isBlockDevice(target)) return "写入块设备：$target"
                index += 2
            } else {
                break
            }
        }
        if (index >= tokens.size) return null

        val program = tokens[index].substringAfterLast('/')
        val args = tokens.subList(index + 1, tokens.size)

        return when (program) {
            "rm" -> checkRm(args)
            "dd" -> checkDd(args)
            "find" -> checkFind(args)
            "mkfs", "mke2fs", "wipefs", "fdisk", "sgdisk", "parted" -> "改写磁盘或分区：$program"
            else -> if (program.startsWith("mkfs.")) "格式化分区：$program" else null
        }
    }

    private fun checkRm(args: List<String>): String? {
        var recursive = false
        val targets = ArrayList<String>()
        for (arg in args) {
            when {
                arg == "--recursive" -> recursive = true
                arg.startsWith("--") -> Unit
                arg.length > 1 && arg.startsWith("-") -> {
                    if (arg.contains('r') || arg.contains('R')) recursive = true
                }
                else -> targets.add(arg)
            }
        }
        if (!recursive) return null
        val hit = targets.firstOrNull { isProtectedPath(it) } ?: return null
        return "递归删除受保护路径：$hit"
    }

    private fun checkDd(args: List<String>): String? {
        for (arg in args) {
            if (!arg.startsWith("of=")) continue
            val target = arg.removePrefix("of=")
            if (isBlockDevice(target)) return "写入块设备：$target"
        }
        return null
    }

    private fun checkFind(args: List<String>): String? {
        if (!args.contains("-delete")) return null
        val root = args.firstOrNull { !it.startsWith("-") } ?: return null
        if (!isProtectedPath(root)) return null
        return "find 递归删除受保护路径：$root"
    }

    private fun isProtectedPath(path: String): Boolean {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == "*") return true
        val normalized = if (trimmed.length > 1) trimmed.trimEnd('/') else trimmed
        return protectedPaths.contains(normalized)
    }

    private fun isBlockDevice(path: String): Boolean {
        return path.startsWith("/dev/block/") ||
                path.startsWith("/dev/mmcblk") ||
                path.startsWith("/dev/sd") ||
                path.startsWith("/dev/disk/")
    }
}
