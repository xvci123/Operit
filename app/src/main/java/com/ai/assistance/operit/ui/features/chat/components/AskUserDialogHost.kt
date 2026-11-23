package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.core.tools.ask.AskAnswer
import com.ai.assistance.operit.core.tools.ask.AskQuestion
import com.ai.assistance.operit.core.tools.ask.AskUserBridge

/**
 * ask_user 的界面宿主（Hermes）。
 *
 * 按 DSHA `ask_user_question` 的批次语义渲染：一次可有多道题；每题独立
 * 单选/多选 + 可补充文本；答案按 question.id 回填。关闭 = DISMISSED，
 * 不做默认选择，也不替模型假设用户同意。
 */
@Composable
fun AskUserDialogHost() {
    val request by AskUserBridge.pending.collectAsState()
    val current = request ?: return

    val selected = remember(current.id) { mutableStateMapOf<String, Set<String>>() }
    val custom = remember(current.id) { mutableStateMapOf<String, String>() }

    AlertDialog(
            onDismissRequest = { AskUserBridge.dismiss() },
            title = {
                Text(
                        if (current.questions.size > 1) "AI 想问你 · ${current.questions.size} 个问题"
                        else "AI 想问你",
                        fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .heightIn(max = 460.dp)
                                        .verticalScroll(rememberScrollState())
                ) {
                    current.questions.forEachIndexed { index, question ->
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        }
                        QuestionBlock(
                                question = question,
                                selected = selected[question.id].orEmpty(),
                                custom = custom[question.id].orEmpty(),
                                onToggle = { label ->
                                    val now = selected[question.id].orEmpty()
                                    selected[question.id] =
                                            if (question.multiSelect) {
                                                if (label in now) now - label else now + label
                                            } else {
                                                setOf(label)
                                            }
                                },
                                onCustomChange = { custom[question.id] = it }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                        onClick = {
                            val answers =
                                    current.questions.map { question ->
                                        AskAnswer(
                                                id = question.id,
                                                selected = selected[question.id].orEmpty().toList(),
                                                custom = custom[question.id]
                                                        ?.trim()
                                                        ?.takeIf { it.isNotEmpty() }
                                        )
                                    }
                            AskUserBridge.submit(answers)
                        }
                ) { Text("提交") }
            },
            dismissButton = {
                TextButton(onClick = { AskUserBridge.dismiss() }) { Text("关闭") }
            }
    )
}

@Composable
private fun QuestionBlock(
        question: AskQuestion,
        selected: Set<String>,
        custom: String,
        onToggle: (String) -> Unit,
        onCustomChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        question.header?.let { header ->
            Text(
                    text = header,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Text(
                text = question.question,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
        )
        if (question.multiSelect) {
            Text(
                    text = "可多选",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
            )
        }

        question.options.forEach { option ->
            Row(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .clickable { onToggle(option.label) }
                                    .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
            ) {
                if (question.multiSelect) {
                    Checkbox(
                            checked = option.label in selected,
                            onCheckedChange = null,
                            modifier = Modifier.padding(end = 4.dp)
                    )
                } else {
                    RadioButton(
                            selected = option.label in selected,
                            onClick = null,
                            modifier = Modifier.padding(end = 4.dp)
                    )
                }
                Column(
                        modifier = Modifier.padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    option.description?.let {
                        Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        OutlinedTextField(
                value = custom,
                onValueChange = onCustomChange,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = false,
                maxLines = 3,
                label = { Text("补充回答（可选）") },
                textStyle = MaterialTheme.typography.bodySmall
        )
    }
}
