package com.ai.assistance.operit.ui.hermes

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 聊天空态：粒子鲸鱼 + 探索未至之境。
 *
 * 时间轴（按用户要求）：
 * ```
 *      0 → 1500ms  零散粒子从四周汇聚（每个粒子有 0~420ms 随机延迟，形成层次）
 *   1500 → 2500ms  停顿 1 秒
 *   2500 → 3200ms  粒子飞到鲸鱼轮廓点，拼出鲸鱼
 *   3200ms 起      鲸鱼实体淡入、粒子淡出，鲸鱼开始上下弹动
 *   3700 → 4200ms  鲸鱼第一次向下弹时，文字浮出（白色、大字距、逐字）
 * ```
 *
 * ## 性能约定
 * 时钟只在 `Canvas` 的绘制 lambda 和 `graphicsLayer` 里读，组合阶段不读，所以整段动画
 * 只有一块 Canvas 重绘 + 两个图层属性变化，不会触发重组。粒子点是一次性采样出来的。
 */
@Composable
fun HermesParticleWhale(modifier: Modifier = Modifier, particleCount: Int = 220) {

    val context = LocalContext.current
    val clock = remember { mutableFloatStateOf(0f) }

    // 一次性从 PNG 的 alpha 通道采样鲸鱼轮廓点（归一化 0..1）
    val targets = remember(particleCount) { sampleWhalePoints(context, particleCount) }
    // 每个粒子的初始散点、随机延迟、相位（固定种子：每次进入一样，不会闪变）
    val starts =
            remember(particleCount) {
                val rnd = Random(20261122)
                List(particleCount) {
                    val angle = rnd.nextFloat() * (2f * PI.toFloat())
                    val dist = 0.55f + rnd.nextFloat() * 0.85f
                    ParticleStart(
                            offset = Offset(0.5f + cos(angle) * dist, 0.5f + sin(angle) * dist),
                            delayMs = rnd.nextFloat() * 420f,
                            phase = rnd.nextFloat()
                    )
                }
            }

    LaunchedEffect(Unit) {
        val startNanos = withFrameNanos { it }
        while (true) {
            val frameNanos = withFrameNanos { it }
            clock.floatValue = (frameNanos - startNanos) / 1_000_000f
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {

        Canvas(modifier = Modifier.fillMaxSize()) {
            val now = clock.floatValue
            val side = minOf(size.width, size.height)
            val whaleRadius = side * 0.30f
            val dotRadius = (side * 0.0085f).coerceIn(1.2f, 4.5f)

            val converge = easeOutCubic(ramp(now, 0f, 1500f))
            val toShape = easeOutCubic(ramp(now, 2500f, 3200f))
            val whaleAlpha = ramp(now, 3150f, 3600f)
            val particlesAlpha = 1f - whaleAlpha * 0.9f

            targets.forEachIndexed { index, target ->
                val s = starts[index]
                val localConverge = easeOutCubic(((now - s.delayMs) / 1500f).coerceIn(0f, 1f))
                val cloudX = 0.5f + cos(s.phase * 6.283f) * 0.055f
                val cloudY = 0.5f + sin(s.phase * 6.283f) * 0.055f
                val cx = s.offset.x + (cloudX - s.offset.x) * localConverge
                val cy = s.offset.y + (cloudY - s.offset.y) * localConverge
                val fx = cx + (target.x - cx) * toShape
                val fy = cy + (target.y - cy) * toShape
                drawCircle(
                        color = Color.White.copy(alpha = (0.16f + 0.72f * converge) * particlesAlpha),
                        radius = dotRadius * (0.55f + 0.75f * s.phase),
                        center = Offset(fx * size.width, fy * size.height)
                )
            }

            // 鲸鱼背后一层柔光，让实体出现时不至于"贴"上去
            if (whaleAlpha > 0.01f) {
                drawCircle(
                        brush =
                                androidx.compose.ui.graphics.Brush.radialGradient(
                                        colors =
                                                listOf(
                                                        Color.White.copy(alpha = 0.09f * whaleAlpha),
                                                        Color.Transparent
                                                ),
                                        center = Offset(size.width / 2f, size.height / 2f),
                                        radius = whaleRadius * 1.6f
                                ),
                        radius = whaleRadius * 1.6f,
                        center = Offset(size.width / 2f, size.height / 2f)
                )
            }
        }

        // 鲸鱼实体：粒子飞到位后淡入，并开始上下弹动（层的属性，不重组）
        Image(
                painter = painterResource(id = R.drawable.whale_alpha),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier =
                        Modifier.size(120.dp).graphicsLayer {
                            val now = clock.floatValue
                            alpha = ramp(now, 3150f, 3600f)
                            val bounceT = ((now - 3200f) / 1400f).coerceAtLeast(0f)
                            val amp = easeOutCubic(ramp(now, 3200f, 3800f))
                            translationY = sin(bounceT * PI.toFloat() * 2f) * 10.dp.toPx() * amp
                        }
        )

        // 文字：鲸鱼第一次向下弹时浮出（用户要求"鲸鱼向下弹动把文字弹出来"）
        Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.size(120.dp))
            Text(
                    text = stringResource(R.string.hermes_tagline),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 8.sp,
                    modifier =
                            Modifier.padding(top = 22.dp).graphicsLayer {
                                val now = clock.floatValue
                                alpha = ramp(now, 3700f, 4200f)
                                // 从下往上浮出，配合鲸鱼向下的那一下
                                translationY = 10.dp.toPx() * (1f - easeOutCubic(ramp(now, 3700f, 4200f)))
                            }
            )
        }
    }
}

private data class ParticleStart(val offset: Offset, val delayMs: Float, val phase: Float)

/** 从鲸鱼 PNG 的 alpha 通道采样轮廓点，归一化到 0..1。 */
private fun sampleWhalePoints(context: android.content.Context, count: Int): List<Offset> {
    val bitmap =
            try {
                BitmapFactory.decodeResource(context.resources, R.drawable.whale_alpha)
            } catch (e: Exception) {
                null
            } ?: return defaultRingPoints(count)

    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return defaultRingPoints(count)

    val step = maxOf(2, minOf(w, h) / 46)
    val candidates = ArrayList<Offset>(1024)
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 110) {
                candidates.add(Offset(x.toFloat() / w, y.toFloat() / h))
            }
            x += step
        }
        y += step
    }
    bitmap.recycle()
    if (candidates.isEmpty()) return defaultRingPoints(count)

    val rnd = Random(7)
    return List(count) { candidates[rnd.nextInt(candidates.size)] }
}

/** 采样失败时的兜底：一圈点，至少不会什么都不画。 */
private fun defaultRingPoints(count: Int): List<Offset> {
    val out = ArrayList<Offset>(count)
    for (i in 0 until count) {
        val a = (i.toFloat() / count) * 2f * PI.toFloat()
        out.add(Offset(0.5f + cos(a) * 0.28f, 0.5f + sin(a) * 0.28f))
    }
    return out
}

private fun ramp(now: Float, from: Float, to: Float): Float {
    if (to <= from) return if (now >= to) 1f else 0f
    return ((now - from) / (to - from)).coerceIn(0f, 1f)
}

private fun easeOutCubic(x: Float): Float {
    val t = 1f - x
    return 1f - t * t * t
}
