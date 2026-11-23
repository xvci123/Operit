package com.ai.assistance.operit.ui.hermes

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 散星 → 直接聚成鲸鱼 → 左上至右下的斜向光束 → 缩小飞入左上角菜单。
 * hold 只延长已归位的最后一帧，不能关闭飞行或把鲸鱼拉回中央。
 */
@Composable
fun HermesSplash(
    flyToHeader: Boolean,
    hold: Boolean = false,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val clock = remember { mutableFloatStateOf(0f) }
    val targets = remember { sampleSplashWhale(context, SPLASH_PARTICLES) }
    val particles = remember { whaleParticles(SPLASH_PARTICLES) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            val elapsed = (withFrameNanos { it } - start) / 1_000_000f
            clock.floatValue = if (hold) elapsed.coerceAtMost(FLY_END_MS) else elapsed
            if (elapsed >= TOTAL_MS) break
        }
        onFinished()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(HermesTokens.Palette.bg)
            .graphicsLayer {
                alpha = if (hold) 1f else 1f - hermesRamp(clock.floatValue, FLY_END_MS, TOTAL_MS)
            }
            .drawBehind {
                val sweep = hermesRamp(clock.floatValue, BEAM_START_MS, BEAM_END_MS)
                if (sweep > 0f && sweep < 1f) {
                    val diagonal = sqrt(size.width * size.width + size.height * size.height)
                    val pulse = sin(sweep * PI.toFloat())
                    val travel = -diagonal * 0.72f + diagonal * 1.44f * sweep
                    val bandWidth = size.width * 0.34f
                    // 竖直光带旋转 45° 后横扫，完整经过左上、中心和右下。
                    withTransform({
                        rotate(degrees = -45f, pivot = center)
                        translate(left = travel)
                    }) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.Transparent,
                                0.28f to Color(0xFF8EBCFF).copy(alpha = 0.07f * pulse),
                                0.46f to Color.White.copy(alpha = 0.34f * pulse),
                                0.54f to Color.White.copy(alpha = 0.34f * pulse),
                                0.72f to Color(0xFF8EBCFF).copy(alpha = 0.07f * pulse),
                                1f to Color.Transparent
                            ),
                            topLeft = Offset(center.x - bandWidth / 2f, -diagonal),
                            size = Size(bandWidth, diagonal * 3f)
                        )
                    }
                }
            }
    ) {
        val startSize = 150.dp
        val startSizePx = with(density) { startSize.toPx() }
        val endSizePx = with(density) { 26.dp.toPx() }
        val startXPx = (constraints.maxWidth - startSizePx) / 2f
        val startYPx = (constraints.maxHeight - startSizePx) / 2f - with(density) { 28.dp.toPx() }
        // 与 TopAppBar 的 48dp 导航按钮中心对齐，最终中心为 (28dp, 状态栏 + 32dp)。
        val endXPx = with(density) { 28.dp.toPx() } - endSizePx / 2f
        val endYPx = with(density) { (statusTop + 32.dp).toPx() } - endSizePx / 2f

        Box(
            modifier = Modifier
                .offset { IntOffset(startXPx.roundToInt(), startYPx.roundToInt()) }
                .requiredSize(startSize)
                .graphicsLayer {
                    val now = clock.floatValue
                    val fly = if (flyToHeader) {
                        hermesEaseInOut(hermesRamp(now, FLY_START_MS, FLY_END_MS))
                    } else 0f
                    val scale = 1f + (endSizePx / startSizePx - 1f) * fly
                    // graphicsLayer 默认绕中心缩放，补回缩放后中心到目标左上角的位移。
                    translationX = (endXPx - startXPx) * fly + (startSizePx - endSizePx) * fly / 2f
                    translationY = (endYPx - startYPx) * fly + (startSizePx - endSizePx) * fly / 2f
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val now = clock.floatValue
                val particleAlpha = 1f - hermesRamp(now, 1450f, 1900f)
                val dotRadius = size.minDimension * 0.0065f
                targets.forEachIndexed { index, target ->
                    val particle = particles[index]
                    val local = hermesRamp(now, 100f + particle.delayMs, 1450f + particle.delayMs)
                    val gather = hermesEaseInOut(local)
                    val shimmer = (1f - gather) * sin(now * 0.004f + particle.phase * 6.283f) * 0.008f
                    val x = particle.start.x + (target.x - particle.start.x) * gather + shimmer
                    val y = particle.start.y + (target.y - particle.start.y) * gather
                    drawCircle(
                        color = Color.White.copy(
                            alpha = (0.4f + particle.phase * 0.6f) * particleAlpha
                        ),
                        radius = dotRadius * (0.6f + particle.phase * 0.8f),
                        center = Offset(x * size.width, y * size.height)
                    )
                }
            }
            Image(
                painter = painterResource(id = R.drawable.whale_alpha),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = hermesRamp(clock.floatValue, 1350f, 1800f)
                }
            )
        }
    }
}

private const val SPLASH_PARTICLES = 680
private const val BEAM_START_MS = 550f
private const val BEAM_END_MS = 1800f
private const val FLY_START_MS = 1950f
private const val FLY_END_MS = 2600f
private const val TOTAL_MS = 2750f

private fun sampleSplashWhale(context: Context, count: Int): List<Offset> {
    val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.whale_alpha)
    val points = ArrayList<Offset>(4096)
    val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 150)
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 110) {
                points.add(Offset(x.toFloat() / bitmap.width, y.toFloat() / bitmap.height))
            }
            x += step
        }
        y += step
    }
    bitmap.recycle()
    return points.shuffled(kotlin.random.Random(23)).take(count)
}
