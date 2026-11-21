package com.ai.assistance.operit.core.tools.ask

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** ask_user 的结果状态。语义借 DSHA 的 /app/ask。 */
enum class AskOutcome {
    ANSWER,
    TIMEOUT,
    DISMISSED,
    BACKGROUND,
    STOPPED
}

/** 一条待回答的问题。 */
data class AskRequest(
        val id: Long,
        val question: String,
        val options: List<String>,
        val timeoutMs: Long
)

/**
 * 模型与界面之间的提问通道（Hermes 新增）。
 *
 * 模型调用 `ask_user` → 工具线程挂起等待 → 界面弹出卡片 → 用户作答 → 工具线程拿到答案。
 * 没有界面监听时不会一直挂着，最迟按超时返回 TIMEOUT。
 */
object AskUserBridge {

    private const val MARK_DISMISS = "__hermes_dismiss__"
    private const val MARK_STOP = "__hermes_stop__"

    private var nextId = 1L

    @Volatile private var waiter: CompletableDeferred<String>? = null

    private val _pending = MutableStateFlow<AskRequest?>(null)

    /** 界面观察这个状态流来弹出卡片。 */
    val pending: StateFlow<AskRequest?> = _pending.asStateFlow()

    /** 工具线程调用：挂起直到用户作答或超时。 */
    suspend fun ask(
            question: String,
            options: List<String>,
            timeoutMs: Long = 120_000L
    ): Pair<AskOutcome, String?> {
        val deferred = CompletableDeferred<String>()
        waiter = deferred
        val request =
                AskRequest(
                        id = nextId++,
                        question = question,
                        options = options,
                        timeoutMs = timeoutMs
                )
        _pending.value = request

        val answer = withTimeoutOrNull(timeoutMs) { deferred.await() }
        _pending.value = null
        waiter = null

        return when {
            answer == null -> AskOutcome.TIMEOUT to null
            answer == MARK_DISMISS -> AskOutcome.DISMISSED to null
            answer == MARK_STOP -> AskOutcome.STOPPED to null
            else -> AskOutcome.ANSWER to answer
        }
    }

    /** 界面上用户提交了答案。 */
    fun submit(answer: String) {
        waiter?.complete(answer)
    }

    /** 界面被用户关掉。 */
    fun dismiss() {
        waiter?.complete(MARK_DISMISS)
    }

    /** 对话被中断。 */
    fun stop() {
        waiter?.complete(MARK_STOP)
    }

    /** 是否有人在等答案（界面据此决定要不要显示卡片）。 */
    fun isWaiting(): Boolean = waiter != null
}
