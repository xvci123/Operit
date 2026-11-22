package com.ai.assistance.operit.ui.hermes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R

/**
 * 空对话页（Hermes）。
 *
 * 没有消息时原来的界面是全黑的，第一眼像"没加载出来"。
 * 这里放鲸鱼 + 品牌句，明确告诉用户"这是新的开始"，也是视觉上的落点。
 *
 * 刻意做得很轻：一张图 + 一行字，不带动画（避免和输入框抢注意力）。
 */
@Composable
fun HermesEmptyChat(modifier: Modifier = Modifier) {
    Column(
            modifier = modifier.fillMaxSize().padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
    ) {
        Image(
                painter = painterResource(id = R.drawable.whale_alpha),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier =
                        Modifier.size(124.dp).graphicsLayer {
                            // 之前 0.42 太暗，真机上几乎看不清；提到 0.82 ——
                            // 既清楚可辨，又不至于抢过输入框
                            alpha = 0.82f
                        }
        )
        Text(
                text = stringResource(R.string.hermes_tagline),
                // 主文字色（#E8EAEF，与 Operit2 源码一致的文字色），不再用弱化的灰
                color = HermesTokens.Palette.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Light,
                // 中文思源系在多数机器上是系统默认；这里靠字距与字重做"轻奢感"，
                // 不引入新字体文件（避免 APK 再大一截）
                letterSpacing = 8.sp,
                modifier = Modifier.padding(top = 20.dp)
        )
    }
}
