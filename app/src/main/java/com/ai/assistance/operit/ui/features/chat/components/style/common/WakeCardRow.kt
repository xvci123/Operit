package com.ai.assistance.operit.ui.features.chat.components.style.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 唤醒卡片（P4 完整版）。
 *
 * 折叠起来只占一行：`↻ 后台任务已完成            ▸`
 * 点开看注入给模型的原文。默认折叠，因为大多数时候人只需要知道"是什么东西叫醒的"。
 *
 * 为什么不复用 Operit 的"查看隐藏消息"弹窗：那是个通用兜底，弹窗一挡就看不见上下文了；
 * 卡片本身就该在流里、贴着这一轮的位置展开。
 */
@Composable
fun WakeCardRow(
        title: String,
        body: String,
        titleColor: Color,
        subtitleColor: Color,
        modifier: Modifier = Modifier,
) {
    var expanded by remember(title, body) { mutableStateOf(false) }

    Column(
            modifier =
                    modifier
                            .fillMaxWidth()
                            .widthIn(max = 520.dp)
                            .background(
                                    color = subtitleColor.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                    text = "\u21bb",
                    color = subtitleColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
            )
            Text(
                    text = title,
                    color = titleColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
            )
            Text(
                    text = if (expanded) "\u25be" else "\u25b8",
                    color = subtitleColor,
                    style = MaterialTheme.typography.labelSmall
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                if (body.isBlank()) {
                    Text(
                            text = "（这条唤醒没有附带正文）",
                            color = subtitleColor,
                            style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                            text = body,
                            color = subtitleColor,
                            style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
