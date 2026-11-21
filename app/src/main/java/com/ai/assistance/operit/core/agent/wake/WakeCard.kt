package com.ai.assistance.operit.core.agent.wake

/**
 * 唤醒卡片（P4）。
 *
 * 唤醒轮次注入的那条用户消息是 `HIDDEN_PLACEHOLDER`（不进界面当用户发言），
 * 但"这一轮是谁叫醒的"必须让人一眼看见。做法最省事也最稳：不改消息表、不改解析器，
 * 只从注入正文的**第一行方括号标题**里取标题，交给占位条渲染。
 *
 * 约定（WakeRouter 生成的正文都遵守）：
 * ```
 * [后台任务已完成]
 * 作业：job-xxx
 * ...
 * ```
 *
 * 第一行不是 `[标题]` 形式（长度 2..40）就当普通隐藏消息，退回原来的通用占位文案。
 */
object WakeCard {

    /** 卡片标题最长多少字。太长的当普通隐藏消息处理，避免把整段话塞进一条竖排文案里。 */
    private const val MAX_TITLE_CHARS = 40

    data class Card(val title: String, val body: String)

    fun parse(content: String): Card? {
        val text = content.trim()
        if (!text.startsWith("[")) return null
        val lineEnd = text.indexOf('\n')
        val firstLine = if (lineEnd < 0) text else text.substring(0, lineEnd)
        val trimmedLine = firstLine.trim()
        if (trimmedLine.length < 3) return null
        if (!trimmedLine.endsWith("]")) return null
        val title = trimmedLine.substring(1, trimmedLine.length - 1).trim()
        if (title.isEmpty() || title.length > MAX_TITLE_CHARS) return null
        val body = if (lineEnd < 0) "" else text.substring(lineEnd + 1).trim()
        return Card(title = title, body = body)
    }

    /** 只取标题；不是卡片形态时返回 null（调用方退回通用占位文案）。 */
    fun titleOf(content: String): String? = parse(content)?.title
}
