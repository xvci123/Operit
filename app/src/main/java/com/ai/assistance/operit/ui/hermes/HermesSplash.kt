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
 * Hermes 冷启动动画。系统启动窗只铺与这里相同的底色，不预画鲸鱼，
 * 避免“先出现一张静态鲸鱼，再跳成动画”的割裂。
 *
 * [flyToHeader] 只有即将进入聊天主界面时才为 true。协议或权限配置尚未完成时，
 * 鲸鱼原地淡出，不会飞向一个尚未出现的聊天顶栏。
 *
 * 所有动画值只在 drawBehind / graphicsLayer 中读取，避免逐帧重组。
 */
@Composable
fun HermesSplash(
        flyToHeader: Boolean,
        /** 主界面还没准备好时保持最后一帧，不能先淡成黑屏。 */
        hold: Boolean = false,
        onFinished: () -> Unit
) {
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
            .graphicsLayer {
                alpha = if (hold) 1f
                else 1f - ramp(clock.floatValue, 2200f, TOTAL_MS)
            }
    ) {
        val whaleCenterX = maxWidth / 2
        val whaleCenterY = maxHeight / 2 - 32.dp
        val targetX = 65.dp
        val targetY = statusTop + 32.dp
        val startSize = 148.dp
        val endSize = 22.dp

        // 背景层。扫光必须独立于径向光的 strength：否则 strength=0 时整段光都被跳过。
        Box(
            modifier = Modifier.fillMaxSize().drawBehind {
                val now = clock.floatValue
                val w = size.width
                val h = size.height
                val center = Offset(w / 2f, h / 2f - 32.dp.toPx())
                val lightIn = ramp(now, 0f, 420f)
                val lightOut = 1f - ramp(now, 850f, 1250f)
                val strength = lightIn * lightOut

                // 真正斜向的一束光：比鲸鱼更早出现，亮芯从左上穿到右下。
                // 不放在光晕的 if 内，保证开场第一时间就有扫光。
                val sweep = ramp(now, 0f, 1050f)
                if (sweep > 0f && sweep < 1f) {
                    val cx = -w * 0.30f + w * 1.60f * sweep
                    val pulse = kotlin.math.sin(sweep * kotlin.math.PI.toFloat())
                    listOf(
                        w * 0.10f to 0.16f,
                        w * 0.025f to 0.70f
                    ).forEach { (band, peak) ->
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = peak * pulse),
                                    Color.Transparent
                                ),
                                start = Offset(cx - band, -h * 0.18f),
                                end = Offset(cx + band, h * 1.18f)
                            )
                        )
                    }
                }

                // 只留很弱的聚焦底光，不拿它替代扫光。
                if (strength > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.035f * strength),
                                Color.Transparent
                            ),
                            center = center,
                            radius = w * (0.20f + 0.18f * lightIn)
                        ),
                        center = center,
                        radius = w * (0.20f + 0.18f * lightIn)
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
                    alpha = 0.95f * rise
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
