package com.ai.assistance.operit.ui.hermes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import kotlin.math.abs

/**
 * Hermes 启动动画（冷启动 2 秒）。定案见 `Hermes-启动动画-定案.md`。
 *
 * ## 为什么这一版整个重写
 *
 * 上一版把动画时钟当普通 state 在**组合阶段**读（`val now = clock.value * 2000`），
 * 结果每一帧都重组整棵树：8 个字母的 Text、渐变背景、鲸鱼 Image 全部重新组合一遍，
 * 真机上就是"卡顿抽帧"。
 *
 * **这不是 PNG 的锅，也不是"代码画 vs 贴图"的区别**——卡的是重组。
 * 用 Canvas 每帧重绘同样有成本，关键永远是"别在组合阶段读动画值"。
 *
 * 这一版：
 *  - 自己用 `withFrameNanos` 跑帧循环，把毫秒数写进 [mutableFloatStateOf]
 *  - **所有动画值只在 `graphicsLayer {}` 里读**（绘制阶段），组合阶段一次都不读
 *  - 每帧只更新图层属性（alpha / translationY / rotationY / translationX），
 *    不重组、不重新测量、不重新排版
 *
 * 时间轴：光 50→950ms；鲸鱼 240→980ms 浮上来；DEEPSEEK 880→1300ms；
 * HARNESS 1340→1700ms；整体 1700→2000ms 淡出。
 */
@Composable
fun HermesSplash(onFinished: () -> Unit) {

    // 已过去的毫秒数。只在 graphicsLayer 里读 → 不触发重组
    val clock = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        val startNanos = withFrameNanos { it }
        while (true) {
            val frameNanos = withFrameNanos { it }
            val elapsedMs = (frameNanos - startNanos) / 1_000_000f
            clock.floatValue = elapsedMs
            if (elapsedMs >= TOTAL_MS) break
        }
        onFinished()
    }

    Box(
            modifier =
                    Modifier.fillMaxSize()
                            .background(Color(0xFF070A12))
                            .graphicsLayer { alpha = 1f - ramp(clock.floatValue, 1700f, 2000f) }
    ) {
        // ---------- 斜光：从左上扫到右下 ----------
        Box(
                modifier =
                        Modifier.fillMaxSize()
                                .graphicsLayer {
                                    val p = ramp(clock.floatValue, 50f, 950f)
                                    val w = size.width
                                    val bandW = w * 0.42f
                                    // 从完全在左侧之外，扫到完全在右下角之外
                                    translationX = -bandW + (w + bandW * 1.9f) * p
                                    // 中间最亮、两端淡出，扫到边缘自然收掉
                                    alpha = 0.9f * (1f - abs(p - 0.5f) * 1.7f).coerceIn(0f, 1f)
                                    rotationZ = 18f
                                    blendMode = BlendMode.Screen
                                }
                                .background(
                                        Brush.horizontalGradient(
                                                listOf(
                                                        Color.Transparent,
                                                        Color.White.copy(alpha = 0.05f),
                                                        Color.White.copy(alpha = 0.62f),
                                                        Color.White.copy(alpha = 0.05f),
                                                        Color.Transparent
                                                )
                                        )
                                )
        )

        // ---------- 鲸鱼 + 品牌字 ----------
        Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
        ) {
            Image(
                    painter = painterResource(id = R.drawable.whale_alpha),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier =
                            Modifier.size(148.dp).graphicsLayer {
                                // 从下方浮上来：位移 + 透明度 + 轻微放大，同一条 ease-out
                                val rise = easeOutCubic(ramp(clock.floatValue, 240f, 980f))
                                alpha = 0.95f * rise
                                translationY = 46.dp.toPx() * (1f - rise)
                                scaleX = 0.93f + 0.07f * rise
                                scaleY = scaleX
                            }
            )

            Box(modifier = Modifier.padding(top = 28.dp)) {
                // DEEPSEEK 逐字翻转落下，1340→1560ms 让位给 HARNESS
                FlipWord(
                        word = "DEEPSEEK",
                        clock = clock,
                        startMs = 880f,
                        staggerMs = 36f,
                        fadeOutFrom = 1340f,
                        fadeOutTo = 1560f
                )
                // HARNESS 逐字翻入
                FlipWord(
                        word = "HARNESS",
                        clock = clock,
                        startMs = 1340f,
                        staggerMs = 40f,
                        fadeOutFrom = Float.MAX_VALUE,
                        fadeOutTo = Float.MAX_VALUE
                )
            }
        }
    }
}

private const val TOTAL_MS = 2000f

/**
 * 逐字翻转。
 *
 * [clock] 传的是 state 本身而不是值——传值会让组合阶段读它，每帧重组一次；
 * 传 state 之后每个字母的文字内容整段动画只组合一次。
 */
@Composable
private fun FlipWord(
        word: String,
        clock: State<Float>,
        startMs: Float,
        staggerMs: Float,
        fadeOutFrom: Float,
        fadeOutTo: Float
) {
    Row {
        word.forEachIndexed { index, ch ->
            Text(
                    text = ch.toString(),
                    style =
                            TextStyle(
                                    color = Color(0xFFE8EEF7),
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 3.sp
                            ),
                    modifier =
                            Modifier.graphicsLayer {
                                val now = clock.value
                                val local =
                                        ((now - startMs - index * staggerMs) / 240f).coerceIn(0f, 1f)
                                val eased = easeOutCubic(local)
                                rotationY = (1f - eased) * 88f
                                cameraDistance = 14f * density
                                val globalFade =
                                        if (fadeOutTo <= fadeOutFrom) 1f
                                        else 1f - ramp(now, fadeOutFrom, fadeOutTo)
                                alpha = eased * globalFade
                            }
            )
        }
    }
}

/** 把 [now] 线性映射到 0..1，区间外夹住。 */
private fun ramp(now: Float, from: Float, to: Float): Float {
    if (to <= from) return if (now >= to) 1f else 0f
    return ((now - from) / (to - from)).coerceIn(0f, 1f)
}

private fun easeOutCubic(x: Float): Float {
    val t = 1f - x
    return 1f - t * t * t
}
