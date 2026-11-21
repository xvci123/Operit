package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.tools.ask.AskUserBridge

/**
 * ask_user 的界面宿主（Hermes 新增）。
 *
 * 挂在聊天页的根布局上即可：模型调用 ask_user 时自动弹出，用户作答或关闭后消失。
 */
@Composable
fun AskUserDialogHost() {
    val request by AskUserBridge.pending.collectAsState()
    val current = request

    if (current == null) {
        return
    }

    var input by remember(current.id) { mutableStateOf("") }

    AlertDialog(
            onDismissRequest = { AskUserBridge.dismiss() },
            title = { Text("AI 想问你") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(current.question)
                    if (current.options.isEmpty()) {
                        OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                singleLine = true,
                                label = { Text("你的回答") }
                        )
                    }
                }
            },
            confirmButton = {
                if (current.options.isEmpty()) {
                    TextButton(
                            onClick = { AskUserBridge.submit(input.trim()) },
                            enabled = input.isNotBlank()
                    ) { Text("回答") }
                } else {
                    Row(horizontalArrangement = Arrangement.End) {
                        current.options.forEach { option ->
                            TextButton(onClick = { AskUserBridge.submit(option) }) {
                                Text(option)
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { AskUserBridge.dismiss() }) { Text("关闭") }
            }
    )
}
