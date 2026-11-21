package com.ai.assistance.operit.ui.hermes

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
 * Hermes 启动动画（P5②，冷启动 2 秒）。
 *
 * 定案见 `Hermes-启动动画-定案.md`，这里是真机版落地：
 *  - 纯黑蓝底 `#070A12`（和聊天页一致）
 *  - 一道白色斜光从左上扫到右下（50ms 起，950ms 前扫完）
 *  - 鲸鱼 350→900ms 淡入
 *  - 900→1300ms DEEPSEEK 逐字翻转落下；1340→1700ms 换成 HARNESS 逐字翻入
 *  - 1700→2000ms 整体淡出，然后回调 onFinished
 *
 * 为什么不做模糊：这台机器是 Android 11（API 30），Compose 的 RenderEffect 模糊要 31。
 * 所以光的边缘靠**渐变透明度**软化，不依赖模糊。
 *
 * 为什么做成覆盖层而不是独立 Activity：覆盖层盖在已经布局好的主界面上，
 * 2 秒后直接撤掉，不多一次 Activity 跳转，也不会闪白屏。
 */
@Composable
fun HermesSplash(onFinished: () -> Unit) {

    val totalMs = 2000f
    // 一条线性时钟推进整段动画，所有元素都由它算出来，方便对着时间轴调
    val clock = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        clock.animateTo(1f, animationSpec = tween(durationMillis = totalMs.toInt(), easing = LinearEasing))
        onFinished()
    }

    val now = clock.value * totalMs

    val whaleAlpha = ramp(now, 350f, 900f, 0f, 0.92f)
    val whaleScale = 0.96f + 0.04f * ramp(now, 350f, 1100f, 0f, 1f)
    val overlayAlpha = 1f - ramp(now, 1700f, 2000f, 0f, 1f)
    val deepseekAlpha = 1f - ramp(now, 1340f, 1560f, 0f, 1f)

    BoxWithConstraints(
            modifier =
                    Modifier.fillMaxSize()
                            .background(Color(0xFF070A12))
                            .graphicsLayer { alpha = overlayAlpha }
    ) {
        val bandWidth = maxWidth * 0.42f
        val travelStart = -bandWidth
        val travelEnd = maxWidth + bandWidth * 0.6f
        val sweep = ramp(now, 50f, 950f, 0f, 1f)
        val bandX = travelStart + (travelEnd - travelStart) * sweep
        // 中间最亮、两端淡出，扫到边缘时自然消失
        val bandAlpha = 0.85f * (1f - abs(sweep - 0.5f) * 1.6f).coerceIn(0f, 1f)

        if (bandAlpha > 0.01f) {
            Box(
                    modifier =
                            Modifier.offset(x = bandX)
                                    .fillMaxHeight()
                                    .width(bandWidth)
                                    .rotate(18f)
                                    .graphicsLayer {
                                        blendMode = BlendMode.Screen
                                        alpha = bandAlpha
                                    }
                                    .background(
                                            Brush.horizontalGradient(
                                                    listOf(
                                                            Color.Transparent,
                                                            Color.White.copy(alpha = 0.05f),
                                                            Color.White.copy(alpha = 0.55f),
                                                            Color.White.copy(alpha = 0.05f),
                                                            Color.Transparent
                                                    )
                                            )
                                    )
            )
        }

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
                                alpha = whaleAlpha
                                scaleX = whaleScale
                                scaleY = whaleScale
                            }
            )

            Box(modifier = Modifier.padding(top = 28.dp)) {
                FlipWord(
                        word = "DEEPSEEK",
                        now = now,
                        startMs = 900f,
                        staggerMs = 40f,
                        alphaAll = deepseekAlpha * ramp(now, 900f, 1000f, 0f, 1f)
                )
                FlipWord(
                        word = "HARNESS",
                        now = now,
                        startMs = 1340f,
                        staggerMs = 40f,
                        alphaAll = ramp(now, 1340f, 1420f, 0f, 1f)
                )
            }
        }
    }
}

/**
 * 逐字翻转：`rotationY` 从 88 度转到 0，配合 cameraDistance 做出"卡片立起来又倒下"的观感，
 * 每个字比前一个字晚 [staggerMs]，形成波浪。
 */
@Composable
private fun FlipWord(
        word: String,
        now: Float,
        startMs: Float,
        staggerMs: Float,
        alphaAll: Float
) {
    if (alphaAll <= 0.01f) return
    Row {
        word.forEachIndexed { index, ch ->
            val local = ((now - startMs - index * staggerMs) / 260f).coerceIn(0f, 1f)
            val eased = easeOutCubic(local)
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
                                rotationY = (1f - eased) * 88f
                                cameraDistance = 14f * density
                                alpha = eased * alphaAll
                            }
            )
        }
    }
}

/** 把 [now] 线性映射到 [outFrom]..[outTo]，区间外夹住。 */
private fun ramp(now: Float, from: Float, to: Float, outFrom: Float, outTo: Float): Float {
    if (to <= from) return outTo
    val p = ((now - from) / (to - from)).coerceIn(0f, 1f)
    return outFrom + (outTo - outFrom) * p
}

private fun easeOutCubic(x: Float): Float {
    val t = 1f - x
    return 1f - t * t * t
}
