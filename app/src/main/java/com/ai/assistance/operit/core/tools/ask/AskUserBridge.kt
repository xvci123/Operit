package com.ai.assistance.operit.core.tools.ask

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** ask_user 的结果状态。语义对齐 DSHA 的 user-questions seam。 */
enum class AskOutcome {
    ANSWER,
    TIMEOUT,
    DISMISSED,
    BACKGROUND,
    STOPPED
}

/** 一个选项。description 对应 DSHA 的 option.description（一句话说明取舍）。 */
data class AskOption(
        val label: String,
        val description: String? = null
)

/** 一道题。id 在一次调用内唯一，答案按 id 回填。 */
data class AskQuestion(
        val id: String,
        val question: String,
        val header: String? = null,
        val options: List<AskOption> = emptyList(),
        val multiSelect: Boolean = false
)

/** 用户对一道题的作答。selected 为空且 custom 为空表示明确跳过。 */
data class AskAnswer(
        val id: String,
        val selected: List<String> = emptyList(),
        val custom: String? = null
)

/** 一次提问的完整结果。 */
data class AskResult(
        val outcome: AskOutcome,
        val answers: List<AskAnswer> = emptyList()
)

/** 一次提问批次（可含多道题）。 */
data class AskRequest(
        val id: Long,
        val questions: List<AskQuestion>,
        val timeoutMs: Long
)

private sealed class AskReply {
    data class Answered(val answers: List<AskAnswer>) : AskReply()
    object Dismissed : AskReply()
    object Stopped : AskReply()
}

/**
 * 模型与界面之间的提问通道。
 *
 * 对齐 DSHA：一次可提交多道题，题与题有稳定 id；答案按 id 回填，允许跳过。
 * 当前仍是一次阻塞等待（没有 timed pending/迟答），超时会明确返回 TIMEOUT，
 * 不把“没回答”当成同意。
 */
object AskUserBridge {

    private var nextId = 1L

    @Volatile private var waiter: CompletableDeferred<AskReply>? = null

    private val _pending = MutableStateFlow<AskRequest?>(null)

    /** 界面观察这个状态流来弹出卡片。 */
    val pending: StateFlow<AskRequest?> = _pending.asStateFlow()

    /** 工具线程调用：挂起直到用户提交整批答案、关闭或超时。 */
    suspend fun ask(
            questions: List<AskQuestion>,
            timeoutMs: Long = 120_000L
    ): AskResult {
        val deferred = CompletableDeferred<AskReply>()
        waiter = deferred
        val request =
                AskRequest(
                        id = nextId++,
                        questions = questions,
                        timeoutMs = timeoutMs
                )
        _pending.value = request

        val reply = withTimeoutOrNull(timeoutMs) { deferred.await() }
        _pending.value = null
        waiter = null

        return when (reply) {
            is AskReply.Answered -> AskResult(AskOutcome.ANSWER, reply.answers)
            AskReply.Dismissed -> AskResult(AskOutcome.DISMISSED)
            AskReply.Stopped -> AskResult(AskOutcome.STOPPED)
            null -> AskResult(AskOutcome.TIMEOUT)
        }
    }

    /** 界面提交整批答案。 */
    fun submit(answers: List<AskAnswer>) {
        waiter?.complete(AskReply.Answered(answers))
    }

    /** 界面被用户关掉。 */
    fun dismiss() {
        waiter?.complete(AskReply.Dismissed)
    }

    /** 对话被中断。 */
    fun stop() {
        waiter?.complete(AskReply.Stopped)
    }

    /** 是否有人在等答案。 */
    fun isWaiting(): Boolean = waiter != null
}
