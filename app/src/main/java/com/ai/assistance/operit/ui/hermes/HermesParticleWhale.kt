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
import androidx.compose.ui.graphics.Brush
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
 * 聊天空态：粒子先汇成与 PNG 完全同形的鲸鱼，实体淡入后只跃动一次，文字浮出，随后静止。
 * 采样点使用图片自身的 0..1 坐标，在 Canvas 内映射到固定 124dp 方框，绝不乘屏幕宽高。
 */
@Composable
fun HermesParticleWhale(modifier: Modifier = Modifier, particleCount: Int = 320) {
    val context = LocalContext.current
    val clock = remember { mutableFloatStateOf(0f) }
    val targets = remember(particleCount) { sampleWhalePoints(context, particleCount) }
    val starts = remember(particleCount) {
        val rnd = Random(20261122)
        List(particleCount) {
            val angle = rnd.nextFloat() * (2f * PI.toFloat())
            val dist = 0.42f + rnd.nextFloat() * 0.38f
            ParticleStart(
                offset = Offset(0.5f + cos(angle) * dist, 0.5f + sin(angle) * dist),
                delayMs = rnd.nextFloat() * 240f,
                phase = rnd.nextFloat()
            )
        }
    }

    LaunchedEffect(Unit) {
        val startNanos = withFrameNanos { it }
        while (true) {
            val elapsedMs = (withFrameNanos { it } - startNanos) / 1_000_000f
            clock.floatValue = elapsedMs
            if (elapsedMs >= FINISH_MS) break
        }
        // 不保持无限 withFrameNanos 循环；动画完成后鲸鱼和文字都真正静止。
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 整体上移，避免鲸鱼落在屏幕下半部；留白只用于定位，不改聊天业务布局。
        Column(
            modifier = Modifier.padding(bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(WHALE_SIZE)) {
                    val now = clock.floatValue
                    val toShape = easeOutCubic(ramp(now, 1100f, 2000f))
                    val whaleAlpha = ramp(now, 1950f, 2350f)
                    val particlesAlpha = 1f - whaleAlpha
                    val dotRadius = (size.minDimension * 0.009f).coerceIn(1.1f, 2.4f)

                    targets.forEachIndexed { index, target ->
                        val particle = starts[index]
                        val local = easeOutCubic(
                            ((now - particle.delayMs) / 1100f).coerceIn(0f, 1f)
                        )
                        val cloudX = 0.5f + cos(particle.phase * 6.283f) * 0.075f
                        val cloudY = 0.5f + sin(particle.phase * 6.283f) * 0.075f
                        val cx = particle.offset.x + (cloudX - particle.offset.x) * local
                        val cy = particle.offset.y + (cloudY - particle.offset.y) * local
                        drawCircle(
                            color = Color.White.copy(
                                alpha = (0.24f + 0.66f * local) * particlesAlpha
                            ),
                            radius = dotRadius * (0.65f + 0.65f * particle.phase),
                            center = Offset(
                                (cx + (target.x - cx) * toShape) * size.width,
                                (cy + (target.y - cy) * toShape) * size.height
                            )
                        )
                    }

                    if (whaleAlpha > 0f) {
                        val radius = size.minDimension * 0.64f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.07f * whaleAlpha),
                                    Color.Transparent
                                ),
                                radius = radius
                            ),
                            radius = radius
                        )
                    }
                }

                Image(
                    painter = painterResource(id = R.drawable.whale_alpha),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(WHALE_SIZE).graphicsLayer {
                        val now = clock.floatValue
                        alpha = 0.9f * ramp(now, 1950f, 2350f)
                        // 只有一次：向上再落回原点。之后恒为 0，不再循环跳动。
                        val bounce = ramp(now, 2350f, 3000f)
                        translationY = -sin(bounce * PI.toFloat()) * 12.dp.toPx()
                    }
                )
            }

            Text(
                text = stringResource(R.string.hermes_tagline),
                color = HermesTokens.Palette.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 8.sp,
                modifier = Modifier.padding(top = 24.dp).graphicsLayer {
                    val appear = easeOutCubic(ramp(clock.floatValue, 2750f, 3300f))
                    alpha = appear
                    translationY = 12.dp.toPx() * (1f - appear)
                }
            )
        }
    }
}

private val WHALE_SIZE = 124.dp
private const val FINISH_MS = 3400f

private data class ParticleStart(val offset: Offset, val delayMs: Float, val phase: Float)

/** 从 alpha 通道无重复抽样，保留轮廓和内部空白；失败时也不能拿椭圆冒充鲸鱼。 */
private fun sampleWhalePoints(context: android.content.Context, count: Int): List<Offset> {
    val bitmap = try {
        BitmapFactory.decodeResource(context.resources, R.drawable.whale_alpha)
    } catch (_: Exception) {
        null
    } ?: return emptyList()

    val candidates = ArrayList<Offset>(2048)
    val step = maxOf(2, minOf(bitmap.width, bitmap.height) / 100)
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 110) {
                candidates.add(Offset(x.toFloat() / bitmap.width, y.toFloat() / bitmap.height))
            }
            x += step
        }
        y += step
    }
    bitmap.recycle()
    if (candidates.isEmpty()) return emptyList()
    return candidates.shuffled(Random(7)).take(count)
}

private fun ramp(now: Float, from: Float, to: Float): Float {
    return ((now - from) / (to - from)).coerceIn(0f, 1f)
}

private fun easeOutCubic(x: Float): Float {
    val t = 1f - x
    return 1f - t * t * t
}
