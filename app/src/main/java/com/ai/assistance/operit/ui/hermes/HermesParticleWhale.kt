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

/** 星光只从散点直接汇成鲸鱼，不经过中心圆、光环或其他中间图形。 */
@Composable
fun HermesParticleWhale(
    modifier: Modifier = Modifier,
    particleCount: Int = 520,
    replayKey: String? = null
) {
    val context = LocalContext.current
    // 每个空对话 id 只在本次进程里播一次。
    // 之前是监听 ON_RESUME 就 resumeToken++ 重播 —— 切后台回来、锁屏亮屏、
    // 权限弹窗点掉、通知下拉回来都会莫名其妙重放一遍，这才是"乱触发"的来源。
    val shouldPlay = remember(replayKey) { HermesWhalePlayback.claim(replayKey) }
    val clock = remember { mutableFloatStateOf(if (shouldPlay) 0f else CHAT_ANIM_END_MS) }
    val targets = remember(particleCount) { sampleWhalePoints(context, particleCount) }
    val particles = remember(particleCount) { whaleParticles(particleCount) }

    LaunchedEffect(replayKey, shouldPlay) {
        if (!shouldPlay) {
            // 已经播过：直接停在最终帧，不闪、不重播。
            clock.floatValue = CHAT_ANIM_END_MS
            return@LaunchedEffect
        }
        clock.floatValue = 0f
        val startNanos = withFrameNanos { it }
        while (true) {
            val elapsed = (withFrameNanos { it } - startNanos) / 1_000_000f
            clock.floatValue = elapsed
            if (elapsed >= CHAT_ANIM_END_MS) break
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(WHALE_SIZE)) {
                    val now = clock.floatValue
                    val shapeProgress = ramp(now, 300f, 1750f)
                    val whaleAlpha = ramp(now, 1550f, 2050f)
                    val particlesAlpha = 1f - ramp(now, 1650f, 2150f)
                    val radius = size.minDimension * 0.009f
                    val gather = easeInOutCubic(shapeProgress)

                    targets.forEachIndexed { index, target ->
                        val particle = particles[index]
                        val local = ramp(now, particle.delayMs, 1450f + particle.delayMs)
                        val progress = easeInOutCubic(local)
                        val drift = (1f - progress) * sin(now * 0.002f + particle.phase * 6.283f) * 0.012f
                        val x = particle.start.x + (target.x - particle.start.x) * progress + drift
                        val y = particle.start.y + (target.y - particle.start.y) * progress + drift * 0.6f
                        val appear = ramp(now, 0f, 240f + particle.delayMs)
                        drawCircle(
                            color = Color.White.copy(alpha = (0.35f + particle.phase * 0.65f) * appear * particlesAlpha),
                            radius = radius * (0.55f + particle.phase * 0.85f) * (0.75f + gather * 0.25f),
                            center = Offset(x * size.width, y * size.height)
                        )
                    }

                    if (whaleAlpha > 0f) {
                        val glowRadius = size.minDimension * 0.58f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.045f * whaleAlpha), Color.Transparent),
                                radius = glowRadius
                            ),
                            radius = glowRadius
                        )
                    }
                }

                Image(
                    painter = painterResource(id = R.drawable.whale_alpha),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(WHALE_SIZE).graphicsLayer {
                        alpha = ramp(clock.floatValue, 1550f, 2050f)
                        val bounce = ramp(clock.floatValue, 2100f, 2650f)
                        translationY = -sin(bounce * PI.toFloat()) * 10.dp.toPx()
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
                    val appear = easeOutCubic(ramp(clock.floatValue, 2450f, 3050f))
                    alpha = appear
                    translationY = 12.dp.toPx() * (1f - appear)
                }
            )
        }
    }
}

internal data class WhaleParticle(
    val start: Offset,
    val delayMs: Float,
    val phase: Float
)

/** 椭圆区域内的独立散点。目标直接取自鲸鱼 alpha，没有圆周/圆环阶段。 */
internal fun whaleParticles(count: Int): List<WhaleParticle> {
    val random = Random(20261123)
    return List(count) {
        val angle = random.nextFloat() * 2f * PI.toFloat()
        val distance = kotlin.math.sqrt(random.nextFloat())
        WhaleParticle(
            start = Offset(
                0.5f + cos(angle) * distance * 0.68f,
                0.5f + sin(angle) * distance * 0.72f
            ),
            delayMs = random.nextFloat() * 300f,
            phase = random.nextFloat()
        )
    }
}

/** 对外保留完整鲸鱼内容；聚鲸由画布负责，绝不显示一个先出现的静态 logo。 */
@Composable
fun HermesWhaleImage(modifier: Modifier, alpha: Float = 1f) {
    Image(
        painter = painterResource(id = R.drawable.whale_alpha),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.graphicsLayer { this.alpha = alpha }
    )
}

private fun sampleWhalePoints(context: android.content.Context, count: Int): List<Offset> {
    val bitmap = try {
        BitmapFactory.decodeResource(context.resources, R.drawable.whale_alpha)
    } catch (_: Exception) {
        null
    } ?: return emptyList()
    val candidates = ArrayList<Offset>(4096)
    val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 140)
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
    return candidates.shuffled(Random(7)).take(count)
}

private val WHALE_SIZE = 124.dp

private const val CHAT_ANIM_END_MS = 3400f

/**
 * 进程内播放闸门。
 *
 * 规则：每个空对话 id 各播一次，进程重启后计数清零。
 * 刻意**不**跟随 Activity 生命周期 —— 只要跟着 ON_RESUME 走，动画就会在
 * 任何一次回到前台时重放，用户感知就是"有时候莫名其妙又聚了一次"。
 */
private object HermesWhalePlayback {
    private const val DEFAULT_KEY = "hermes-empty-chat"
    private val played = mutableSetOf<String>()

    fun claim(key: String?): Boolean = played.add(key ?: DEFAULT_KEY)
}

internal fun hermesRamp(now: Float, from: Float, to: Float): Float {
    if (to <= from) return if (now >= to) 1f else 0f
    return ((now - from) / (to - from)).coerceIn(0f, 1f)
}

private fun ramp(now: Float, from: Float, to: Float) = hermesRamp(now, from, to)

internal fun hermesEaseOut(x: Float): Float {
    val t = 1f - x
    return 1f - t * t * t
}

internal fun hermesEaseInOut(x: Float): Float =
    if (x < 0.5f) 4f * x * x * x
    else 1f - ((-2f * x + 2f) * (-2f * x + 2f) * (-2f * x + 2f)) / 2f

private fun easeOutCubic(x: Float) = hermesEaseOut(x)
private fun easeInOutCubic(x: Float) = hermesEaseInOut(x)
