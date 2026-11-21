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
        // 1) 任意位置的输出重定向到块设备
        for (i in tokens.indices) {
            val token = tokens[i]
            if ((token == ">" || token == ">>") &&
                    i + 1 < tokens.size &&
                    isBlockDevice(tokens[i + 1])) {
                return "写入块设备：${tokens[i + 1]}"
            }
        }

        // 2) 跳过提权/包装前缀（sudo、busybox、nohup…）
        var index = 0
        while (index < tokens.size) {
            val name = tokens[index].substringAfterLast('/')
            val isPrefix =
                    name == "sudo" ||
                            name == "doas" ||
                            name == "nohup" ||
                            name == "time" ||
                            name == "nice" ||
                            name == "busybox" ||
                            name == "toybox" ||
                            name == "env"
            if (isPrefix || name.startsWith("-")) index++ else break
        }
        if (index >= tokens.size) return null

        // 3) 前缀后面可能还夹着参数（sudo -u root rm …），向后找真正的危险程序
        var program = tokens[index].substringAfterLast('/')
        if (index > 0 && !isKnownProgram(program)) {
            for (i in index until tokens.size) {
                val name = tokens[i].substringAfterLast('/')
                if (isKnownProgram(name)) {
                    index = i
                    program = name
                    break
                }
            }
        }
        val args = tokens.subList(index + 1, tokens.size)

        // 4) 穿透 shell 包装：su -c "…" / sh -c "…"
        if (program == "su" || program == "sh" || program == "bash" || program == "ash") {
            val position = args.indexOf("-c")
            return if (position >= 0 && position + 1 < args.size) {
                catastrophicReason(args[position + 1])
            } else {
                null
            }
        }

        return when (program) {
            "rm" -> checkRm(args)
            "dd" -> checkDd(args)
            "find" -> checkFind(args)
            "mkfs", "mke2fs", "wipefs", "fdisk", "sgdisk", "parted" -> "改写磁盘或分区：$program"
            else -> if (program.startsWith("mkfs.")) "格式化分区：$program" else null
        }
    }

    private fun isKnownProgram(name: String): Boolean {
        return when (name) {
            "rm",
            "dd",
            "find",
            "mkfs",
            "mke2fs",
            "wipefs",
            "fdisk",
            "sgdisk",
            "parted",
            "su",
            "sh",
            "bash",
            "ash" -> true
            else -> name.startsWith("mkfs.")
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
