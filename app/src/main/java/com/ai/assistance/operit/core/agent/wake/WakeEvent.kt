package com.ai.assistance.operit.core.agent.wake

import org.json.JSONObject

/**
 * Hermes 唤醒事件（P2）。
 *
 * 为什么要有这一层：Operit 里"该开始新一轮"的来源太杂——定时任务、Tasker、外部 Intent、
 * 语音、开机、后台作业结算、子代理返回。如果每个来源各自去调 `sendUserMessage`，就会撞上三个
 * 老问题：抢同一个会话、自己唤醒自己转圈、用户被后台任务打断。所以全部收敛成一个不可变事件，
 * 由 [WakeRouter] 统一做幂等、排队、忙闲分流。
 *
 * 铁律：**任何异步源都不许直接调模型**，只能 submit 一个事件。
 */
enum class WakeSourceKind(
        /** 卡片标题（外部自动化与内部自唤醒在聊天里长得不一样，便于区分谁叫醒的）。 */
        val cardTitle: String
) {
    // ===== 外部自动化：用户可感知的事件 =====
    EXTERNAL_TIMER("⏱ 定时任务"),
    EXTERNAL_TASKER("⏱ 外部自动化"),
    EXTERNAL_INTENT("⏱ 外部调用"),
    EXTERNAL_VOICE("⏱ 语音唤醒"),
    EXTERNAL_BOOT("⏱ 开机任务"),

    // ===== 内部自唤醒：AI 自己的事 =====
    INTERNAL_GOAL("↻ 继续执行目标"),
    INTERNAL_SUBAGENT("⤵ 子代理已返回"),
    INTERNAL_JOB("⏹ 后台任务已完成"),
    INTERNAL_MANUAL("↻ 手动续跑");

    /** 外部来源优先于内部来源：外部是用户能看见的事件，内部是 AI 自己的事。 */
    val isExternal: Boolean
        get() =
                when (this) {
                    EXTERNAL_TIMER,
                    EXTERNAL_TASKER,
                    EXTERNAL_INTENT,
                    EXTERNAL_VOICE,
                    EXTERNAL_BOOT -> true
                    else -> false
                }
}

/**
 * 事件状态。
 *
 *  - [PENDING] 已落盘、还没轮到（进程死了下次启动接着派发）
 *  - [DISPATCHED] 已经把这一轮踢起来了，等这一轮跑完
 *  - [DONE] 这一轮正常结束
 *  - [CONSUMED] 结果在别处被收走了（比如 AI 自己 await 过作业），不必再叫醒
 *  - [CANCELLED] 人手动取消，或目标会话没了
 */
enum class WakeStatus {
    PENDING,
    DISPATCHED,
    DONE,
    CONSUMED,
    CANCELLED
}

/**
 * 一条唤醒事件。
 *
 * @param sourceId 幂等键。同一个事件重复投递只认一次——外部定时器重入、作业重复结算、
 *   进程重启后重放日志，都靠它挡住。
 * @param targetChatId 目标会话；null 表示"投到新建会话"。
 * @param payload 注入模型的原文（原样进入对话历史，不是系统提示）。
 * @param visible 是否在聊天里显示 turn-trigger 卡片。
 */
data class WakeEvent(
        val id: String,
        val sourceKind: WakeSourceKind,
        val sourceId: String,
        val targetChatId: String?,
        val payload: String,
        val visible: Boolean = true,
        val createdAt: Long = System.currentTimeMillis(),
        var status: WakeStatus = WakeStatus.PENDING,
        var attempts: Int = 0,
        var dispatchedAt: Long? = null,
        var finishedAt: Long? = null,
        var note: String = ""
) {

    fun toJson(): JSONObject =
            JSONObject().apply {
                put("id", id)
                put("sourceKind", sourceKind.name)
                put("sourceId", sourceId)
                put("targetChatId", targetChatId ?: JSONObject.NULL)
                put("payload", payload)
                put("visible", visible)
                put("createdAt", createdAt)
                put("status", status.name)
                put("attempts", attempts)
                put("dispatchedAt", dispatchedAt ?: JSONObject.NULL)
                put("finishedAt", finishedAt ?: JSONObject.NULL)
                put("note", note)
            }

    companion object {

        /** 事件日志的一行能不能读成事件；读不出来就整行丢弃（旧版本字段变更时不至于卡死）。 */
        fun fromJson(obj: JSONObject): WakeEvent? {
            return try {
                WakeEvent(
                        id = obj.getString("id"),
                        sourceKind =
                                try {
                                    WakeSourceKind.valueOf(obj.getString("sourceKind"))
                                } catch (e: Exception) {
                                    WakeSourceKind.INTERNAL_MANUAL
                                },
                        sourceId = obj.optString("sourceId"),
                        targetChatId =
                                if (obj.isNull("targetChatId")) null
                                else obj.optString("targetChatId").takeIf { it.isNotBlank() },
                        payload = obj.optString("payload"),
                        visible = obj.optBoolean("visible", true),
                        createdAt = obj.optLong("createdAt"),
                        status =
                                try {
                                    WakeStatus.valueOf(obj.getString("status"))
                                } catch (e: Exception) {
                                    WakeStatus.PENDING
                                },
                        attempts = obj.optInt("attempts"),
                        dispatchedAt =
                                if (obj.isNull("dispatchedAt")) null else obj.optLong("dispatchedAt"),
                        finishedAt =
                                if (obj.isNull("finishedAt")) null else obj.optLong("finishedAt"),
                        note = obj.optString("note")
                )
            } catch (e: Exception) {
                null
            }
        }

        private fun newId(prefix: String): String =
                "$prefix-${System.currentTimeMillis().toString(36)}-${(0..999).random()}"

        /** 后台作业结算。`sourceId` 带作业 id，作业重复结算也只认一次。 */
        fun forJobSettled(
                jobId: String,
                ownerChatId: String?,
                title: String,
                command: String,
                statusText: String,
                elapsedText: String,
                note: String
        ): WakeEvent {
            val payload =
                    buildString {
                        append("[后台任务已完成]\n")
                        append("作业：").append(jobId).append('\n')
                        append("标题：").append(title).append('\n')
                        append("状态：").append(statusText)
                        append("（已运行 ").append(elapsedText).append("）\n")
                        append("命令：").append(command).append('\n')
                        if (note.isNotBlank()) append("备注：").append(note).append('\n')
                        append('\n')
                        append("用 job_output(job_id=\"").append(jobId).append("\") 取输出（默认取结尾 16000 字符，")
                        append("要看启动阶段的报错就传 from_head=true）。\n")
                        append("这是你自己挂的后台作业，结果已经在这里了：不要重复启动同一个作业，也不要 sleep 等它。")
                    }
            return WakeEvent(
                    id = newId("wake"),
                    sourceKind = WakeSourceKind.INTERNAL_JOB,
                    sourceId = "job:$jobId:settled",
                    targetChatId = ownerChatId,
                    payload = payload
            )
        }

        /** 子代理返回父会话（P3 用，先留好入口，保证只走一条投递通道）。 */
        fun forSubagentReturn(
                subChatId: String,
                parentChatId: String?,
                summary: String
        ): WakeEvent {
            val payload =
                    buildString {
                        append("[子代理已返回]\n")
                        append("子会话：").append(subChatId).append('\n')
                        if (summary.isNotBlank()) {
                            append("摘要：\n").append(summary).append('\n')
                        }
                        append("\n子会话的对话记录不在你的上下文里，需要细节就用 ")
                        append("get_chat_messages(chat_id=\"").append(subChatId).append("\") 去取。")
                    }
            return WakeEvent(
                    id = newId("wake"),
                    sourceKind = WakeSourceKind.INTERNAL_SUBAGENT,
                    sourceId = "subagent:$subChatId:returned",
                    targetChatId = parentChatId,
                    payload = payload
            )
        }

        /** 目标续轮：一轮答完但目标没完成，需要自己再跑一轮。 */
        fun forGoalContinuation(chatId: String?, goalText: String): WakeEvent {
            val payload =
                    buildString {
                        append("[继续执行目标]\n")
                        append("目标：").append(goalText).append('\n')
                        append("\n上一轮已经结束。继续推进，不要复述已经做完的部分；")
                        append("如果目标已经达成，就在回复里明确写\"目标已完成\"，之后不要再唤醒自己。")
                    }
            return WakeEvent(
                    id = newId("wake"),
                    sourceKind = WakeSourceKind.INTERNAL_GOAL,
                    // 目标续轮不是一次性事件，按时间分桶，避免被幂等吃掉后续每一轮
                    sourceId = "goal:$chatId:${System.currentTimeMillis() / 1000}",
                    targetChatId = chatId,
                    payload = payload
            )
        }

        /** 外部自动化（定时 / Tasker / Intent / 语音 / 开机）。`triggerId` 由调用方保证同一次触发只用一次。 */
        fun forExternal(
                kind: WakeSourceKind,
                triggerId: String,
                chatId: String?,
                prompt: String
        ): WakeEvent {
            val payload =
                    buildString {
                        append('[').append(kind.cardTitle.trimStart('⏱', ' ')).append("]\n")
                        append(prompt)
                    }
            return WakeEvent(
                    id = newId("wake"),
                    sourceKind = kind,
                    sourceId = "$triggerId",
                    targetChatId = chatId,
                    payload = payload
            )
        }
    }
}

/**
 * 当前正在跑的那一轮属于哪个会话。
 *
 * 用途只有一个：后台作业 / 子代理结算时知道该把唤醒投回哪个会话。工具层拿不到 chatId
 * （`ToolExecutor.invoke(tool)` 里没有会话上下文），所以在真正的会话入口处记一笔。
 *
 * 记的是"最近一个真正开跑的会话"而不是"默认会话"：用户可能同时在悬浮窗里跑另一个会话。
 */
object TurnContext {

    @Volatile private var currentChatId: String? = null

    @Volatile private var lastActiveAt: Long = 0L

    fun enter(chatId: String?) {
        if (chatId.isNullOrBlank()) return
        currentChatId = chatId
        lastActiveAt = System.currentTimeMillis()
    }

    /** 取最近活跃的会话；太久没动（默认 30 分钟）就当没有，免得把唤醒投到一个早就不用的会话。 */
    fun current(maxAgeMs: Long = 30 * 60 * 1000L): String? {
        val id = currentChatId ?: return null
        if (System.currentTimeMillis() - lastActiveAt > maxAgeMs) return null
        return id
    }
}
