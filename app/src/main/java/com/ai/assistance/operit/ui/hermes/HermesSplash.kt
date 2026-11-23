package com.ai.assistance.operit.ui.hermes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R

/**
 * Hermes 冷启动动画。系统启动窗负责首帧之前的静态鲸鱼，本层负责接管后的动效。
 *
 * [flyToHeader] 只有即将进入聊天主界面时才为 true。协议或权限配置尚未完成时，
 * 鲸鱼原地淡出，不会飞向一个尚未出现的聊天顶栏。
 *
 * 所有动画值只在 drawBehind / graphicsLayer 中读取，避免逐帧重组。
 */
@Composable
fun HermesSplash(flyToHeader: Boolean, onFinished: () -> Unit) {
    val clock = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        val startNanos = withFrameNanos { it }
        while (true) {
            val elapsedMs = (withFrameNanos { it } - startNanos) / 1_000_000f
            clock.floatValue = elapsedMs
            if (elapsedMs >= TOTAL_MS) break
        }
        onFinished()
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(HermesTokens.Palette.bg)
            .graphicsLayer { alpha = 1f - ramp(clock.floatValue, 2200f, TOTAL_MS) }
    ) {
        val whaleCenterX = maxWidth / 2
        val whaleCenterY = maxHeight / 2 - 32.dp
        val targetX = 28.dp
        val targetY = statusTop + 32.dp
        val startSize = 148.dp
        val endSize = 24.dp

        // 局部柔光，而不是覆盖全屏的白色渐变。任何一帧都不会把整屏刷白。
        Box(
            modifier = Modifier.fillMaxSize().drawBehind {
                val now = clock.floatValue
                val lightIn = ramp(now, 0f, 420f)
                val lightOut = 1f - ramp(now, 850f, 1250f)
                val strength = lightIn * lightOut
                if (strength <= 0f) return@drawBehind
                val center = Offset(size.width / 2f, size.height / 2f - 32.dp.toPx())
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.18f * strength),
                            HermesTokens.Palette.primary.copy(alpha = 0.06f * strength),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.width * (0.22f + 0.25f * lightIn)
                    ),
                    center = center,
                    radius = size.width * (0.22f + 0.25f * lightIn)
                )

                // 斜向光扫：参考图要的“开场有一道光线”。峰值只有 0.10，
                // 并且是窄带渐变，不是整屏刷白；两端全透明，不会露矩形边。
                val sweep = ramp(now, 0f, 1150f)
                if (sweep > 0f && sweep < 1f) {
                    val w = size.width
                    val h = size.height
                    val band = w * 0.52f
                    val cx = -band + (w + band * 2f) * sweep
                    val peak = 0.10f * kotlin.math.sin(sweep * kotlin.math.PI.toFloat())
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = peak),
                                Color.Transparent
                            ),
                            start = Offset(cx - band, -h * 0.15f),
                            end = Offset(cx + band, h * 1.15f)
                        )
                    )
                }

                // 四周向中心收暗；中心始终透明，不会出现矩形光边或全白帧。
                val gather = easeOutCubic(ramp(now, 900f, 1550f))
                if (gather > 0f) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                HermesTokens.Palette.bg.copy(alpha = 0.45f * gather),
                                HermesTokens.Palette.bg.copy(alpha = gather)
                            ),
                            center = center,
                            radius = maxOf(size.width, size.height) * (0.95f - 0.45f * gather)
                        )
                    )
                }
            }
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.whale_alpha),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(startSize).graphicsLayer {
                    val now = clock.floatValue
                    val rise = easeOutCubic(ramp(now, 0f, 650f))
                    val fly = if (flyToHeader) {
                        easeInOutCubic(ramp(now, 1750f, 2200f))
                    } else {
                        0f
                    }
                    alpha = 0.95f * (0.35f + 0.65f * rise)
                    translationX = (targetX - whaleCenterX).toPx() * fly
                    translationY = (targetY - whaleCenterY).toPx() * fly +
                        24.dp.toPx() * (1f - rise) * (1f - fly)
                    val shrink = 1f - (1f - endSize / startSize) * fly
                    scaleX = (0.96f + 0.04f * rise) * shrink
                    scaleY = scaleX
                }
            )

            Box(modifier = Modifier.padding(top = 28.dp)) {
                Box {
                    FlipWord(
                        word = "DEEPSEEK",
                        clock = clock,
                        startMs = 480f,
                        staggerMs = 30f,
                        fadeOutFrom = 1100f,
                        fadeOutTo = 1280f,
                        hollow = false
                    )
                    FlipWord(
                        word = "HARNESS",
                        clock = clock,
                        startMs = 1250f,
                        staggerMs = 32f,
                        fadeOutFrom = 1720f,
                        fadeOutTo = 1900f,
                        hollow = true
                    )
                }
            }
        }
    }
}

private const val TOTAL_MS = 2400f

/** [clock] 传 State 本身，组合阶段不读值。两个词先后替换，不并排重叠。 */
@Composable
private fun FlipWord(
    word: String,
    clock: State<Float>,
    startMs: Float,
    staggerMs: Float,
    fadeOutFrom: Float,
    fadeOutTo: Float,
    hollow: Boolean
) {
    Row {
        word.forEachIndexed { index, ch ->
            Text(
                text = ch.toString(),
                style = TextStyle(
                    color = HermesTokens.Palette.text,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    drawStyle = if (hollow) Stroke(width = 1.6f) else null
                ),
                modifier = Modifier.graphicsLayer {
                    val local = ((clock.value - startMs - index * staggerMs) / 240f)
                        .coerceIn(0f, 1f)
                    val eased = easeOutCubic(local)
                    val fadeOut = if (fadeOutTo <= fadeOutFrom) 1f
                        else 1f - ramp(clock.value, fadeOutFrom, fadeOutTo)
                    rotationY = (1f - eased) * 88f
                    cameraDistance = 14f * density
                    alpha = eased * fadeOut
                }
            )
        }
    }
}

private fun ramp(now: Float, from: Float, to: Float): Float {
    if (to <= from) return if (now >= to) 1f else 0f
    return ((now - from) / (to - from)).coerceIn(0f, 1f)
}

private fun easeOutCubic(x: Float): Float {
    val t = 1f - x
    return 1f - t * t * t
}

private fun easeInOutCubic(x: Float): Float {
    return if (x < 0.5f) 4f * x * x * x
    else 1f - ((-2f * x + 2f) * (-2f * x + 2f) * (-2f * x + 2f)) / 2f
}
