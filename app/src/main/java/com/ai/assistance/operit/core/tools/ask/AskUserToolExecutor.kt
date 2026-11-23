package com.ai.assistance.operit.core.tools.ask

import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolValidationResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * `ask_user`：模型主动向用户提问并等待整批回答。
 *
 * 对齐 DSHA `ask_user_question` 的数据形状：
 * ```
 * questions: [
 *   { id, question, header?, options?: [{label, description?}], multi_select? }
 * ]
 * ```
 * 返回紧凑 JSON：`{"answers":[{"id","selected":[...],"custom"?}]}`。
 * 为兼容旧调用，仍接受单问题参数 `question` + `options`（`|` 分隔）。
 */
class AskUserToolExecutor : ToolExecutor {

    companion object {
        const val TOOL_NAME = "ask_user"
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }

    override fun invoke(tool: AITool): ToolResult {
        val questions = parseQuestions(tool)
        if (questions.isEmpty()) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "questions 不能为空"
            )
        }

        val timeoutMs = parseTimeout(tool)
        val result = runBlocking { AskUserBridge.ask(questions, timeoutMs) }

        val text =
                when (result.outcome) {
                    AskOutcome.ANSWER -> answerJson(result.answers)
                    AskOutcome.TIMEOUT ->
                            "TIMEOUT: 用户未在 ${timeoutMs / 1000} 秒内作答，请据此决定下一步，不要假设用户同意"
                    AskOutcome.DISMISSED -> "DISMISSED: 用户关闭了这个问题，没有作答"
                    AskOutcome.BACKGROUND -> "BACKGROUND: 应用不在前台，无法向用户提问"
                    AskOutcome.STOPPED -> "STOPPED: 对话已被中断"
                }

        return ToolResult(
                toolName = tool.name,
                success = result.outcome == AskOutcome.ANSWER,
                result = StringResultData(text)
        )
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        return if (parseQuestions(tool).isEmpty()) {
            ToolValidationResult(valid = false, errorMessage = "questions 参数必填")
        } else {
            ToolValidationResult(valid = true)
        }
    }

    private fun parseTimeout(tool: AITool): Long {
        val ms = tool.parameters.find { it.name == "timeout_ms" }?.value?.toLongOrNull()
        if (ms != null) return ms.coerceAtLeast(1_000L)
        // DSHA 用秒；允许模型传 timeout（秒）作为兼容。
        val seconds = tool.parameters.find { it.name == "timeout" }?.value?.toLongOrNull()
        return if (seconds != null) (seconds * 1000L).coerceAtLeast(1_000L) else DEFAULT_TIMEOUT_MS
    }

    private fun parseQuestions(tool: AITool): List<AskQuestion> {
        val raw = tool.parameters.find { it.name == "questions" }?.value?.trim().orEmpty()
        val fromArray = if (raw.isNotEmpty()) parseQuestionsJson(raw) else emptyList()
        if (fromArray.isNotEmpty()) return fromArray

        // 旧版单问题兼容。
        val question = tool.parameters.find { it.name == "question" }?.value?.trim().orEmpty()
        if (question.isEmpty()) return emptyList()
        val options =
                (tool.parameters.find { it.name == "options" }?.value ?: "")
                        .split("|")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { AskOption(it) }
        return listOf(AskQuestion(id = "q1", question = question, options = options))
    }

    private fun parseQuestionsJson(raw: String): List<AskQuestion> {
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            // 某些模型会包一层 {"questions":[...]}。
            try {
                JSONObject(raw).optJSONArray("questions")
            } catch (_: Exception) {
                null
            }
        } ?: return emptyList()

        val out = ArrayList<AskQuestion>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val question = obj.optString("question").trim()
            if (question.isEmpty()) continue
            val id = obj.optString("id").trim().ifEmpty { "q${i + 1}" }
            val header = obj.optString("header").trim().ifEmpty { null }
            val options = parseOptions(obj.opt("options"))
            val multi = obj.optBoolean("multi_select", obj.optBoolean("multiSelect", false))
            out.add(
                    AskQuestion(
                            id = uniqueId(out, id),
                            question = question,
                            header = header,
                            options = options,
                            multiSelect = multi
                    )
            )
        }
        return out
    }

    private fun parseOptions(value: Any?): List<AskOption> {
        return when (value) {
            is JSONArray -> buildList {
                for (i in 0 until value.length()) {
                    when (val item = value.opt(i)) {
                        is JSONObject -> {
                            val label = item.optString("label").trim()
                            if (label.isNotEmpty()) {
                                add(AskOption(label, item.optString("description").trim().ifEmpty { null }))
                            }
                        }
                        is String -> if (item.isNotBlank()) add(AskOption(item.trim()))
                    }
                }
            }
            is String -> value.split("|").map { it.trim() }.filter { it.isNotEmpty() }.map { AskOption(it) }
            else -> emptyList()
        }
    }

    private fun uniqueId(existing: List<AskQuestion>, candidate: String): String {
        if (existing.none { it.id == candidate }) return candidate
        var n = 2
        while (existing.any { it.id == "$candidate-$n" }) n++
        return "$candidate-$n"
    }

    private fun answerJson(answers: List<AskAnswer>): String {
        val array = JSONArray()
        answers.forEach { answer ->
            val obj = JSONObject()
            obj.put("id", answer.id)
            val selected = JSONArray()
            answer.selected.forEach { selected.put(it) }
            obj.put("selected", selected)
            val custom = answer.custom?.trim().orEmpty()
            if (custom.isNotEmpty()) obj.put("custom", custom)
            array.put(obj)
        }
        return JSONObject().put("answers", array).toString()
    }
}
