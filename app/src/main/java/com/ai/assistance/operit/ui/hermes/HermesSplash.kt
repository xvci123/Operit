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
 * Hermes 启动动画（冷启动，2 秒）。
 *
 * 时间轴：
 * ```
 *   0 →  50ms   纯黑
 *  50 → 950ms   白光斜扫（整屏渐变，端点全透明，不存在矩形边/截断）
 * 950 →1500ms   四周黑色向鲸鱼汇聚（径向收拢，cubic ease-out）
 * 1500→1980ms   鲸鱼飞向左上角鲸鱼图标位置，同时缩到 24dp，贝塞尔缓入缓出
 * 1900→2000ms   覆盖层淡出，与主界面无缝衔接（主界面左上角就是同一只鲸鱼、同一位置同一尺寸）
 * ```
 *
 * ## 性能约定（上一版踩过的坑）
 * 动画时钟只在 `graphicsLayer {}` / `drawBehind {}` 里读，**组合阶段一次都不读**，
 * 否则每帧重组整棵树，真机就是"卡顿抽帧"。`FlipWord` 传 `State<Float>` 而不是值，
 * 就是为了不让组合阶段读它。
 */
@Composable
fun HermesSplash(onFinished: () -> Unit) {

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

    // 状态栏高度：主界面左上角图标在 appBar 里往下让了状态栏高度，
    // 鲸鱼最后要停在同一处，必须算进去，否则接缝会跳一下
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    BoxWithConstraints(
            modifier =
                    Modifier.fillMaxSize()
                            .background(HermesTokens.Palette.bg)
                            .graphicsLayer { alpha = 1f - ramp(clock.floatValue, 1900f, 2000f) }
    ) {
        // 鲸鱼静止时的中心（Column 居中，下方有文字，视觉中心略微上移）
        val whaleCenterX = maxWidth / 2
        val whaleCenterY = maxHeight / 2 - 32.dp
        // 落点 = 主界面 TopAppBar 里鲸鱼图标的中心
        val targetX = 28.dp
        val targetY = statusTop + 32.dp
        val startSize = 148.dp
        val endSize = 24.dp

        // ---------- 1) 白光斜扫 ----------
        Box(
                modifier =
                        Modifier.fillMaxSize().drawBehind {
                            val p = ramp(clock.floatValue, 50f, 950f)
                            if (p <= 0f || p >= 1f) return@drawBehind
                            val w = size.width
                            val h = size.height
                            val bandW = w * 1.15f
                            val cx = -bandW + (w + bandW * 2.2f) * p
                            // 两层叠加：宽而淡打底 + 窄而亮主光，做出层次
                            listOf(1.00f to 0.32f, 0.52f to 0.26f).forEach { (scale, peak) ->
                                val half = bandW * 0.5f * scale
                                drawRect(
                                        brush =
                                                Brush.linearGradient(
                                                        colors =
                                                                listOf(
                                                                        Color.Transparent,
                                                                        Color.White.copy(alpha = peak),
                                                                        Color.Transparent
                                                                ),
                                                        start = Offset(cx - half, -h * 0.2f),
                                                        end = Offset(cx + half, h * 1.2f)
                                                )
                                )
                            }
                        }
        )

        // ---------- 2) 黑色向鲸鱼汇聚 ----------
        // 整屏铺一层"背景色的径向渐变"，中心透明露出鲸鱼，半径由大收小。
        // 用渐变而不是遮罩圈，边缘自然过渡，不会有硬边或断层。
        Box(
                modifier =
                        Modifier.fillMaxSize().drawBehind {
                            val raw = ramp(clock.floatValue, 950f, 1500f)
                            if (raw <= 0f) return@drawBehind
                            val gathered = easeOutCubic(raw)
                            val w = size.width
                            val h = size.height
                            val maxDim = maxOf(w, h)
                            val radius = maxDim * (1.25f - 0.95f * gathered)
                            val center = Offset(w / 2f, h / 2f - 32.dp.toPx())
                            drawRect(
                                    brush =
                                            Brush.radialGradient(
                                                    colors =
                                                            listOf(
                                                                    Color.Transparent,
                                                                    HermesTokens.Palette.bg.copy(alpha = 0.65f),
                                                                    HermesTokens.Palette.bg
                                                            ),
                                                    center = center,
                                                    radius = radius
                                            )
                            )
                        }
        )

        // ---------- 3) 鲸鱼 + 品牌字 ----------
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
                            Modifier.size(startSize).graphicsLayer {
                                val now = clock.floatValue
                                // 浮入：位移 + 透明度 + 轻微放大，同一条 ease-out
                                val rise = easeOutCubic(ramp(now, 240f, 980f))
                                // 飞向左上角：贝塞尔缓入缓出，不是匀速直线
                                val fly = easeInOutCubic(ramp(now, 1500f, 1980f))
                                alpha = 0.95f * rise
                                translationX = (targetX - whaleCenterX).toPx() * fly
                                translationY =
                                        (targetY - whaleCenterY).toPx() * fly +
                                                46.dp.toPx() * (1f - rise) * (1f - fly)
                                val shrink = 1f - (1f - (endSize / startSize)) * fly
                                scaleX = (0.93f + 0.07f * rise) * shrink
                                scaleY = scaleX
                            }
            )

            Box(modifier = Modifier.padding(top = 28.dp)) {
                // 品牌字：鲸鱼起飞前必须收掉，否则会跟着飞出屏幕
                Box(
                        modifier =
                                Modifier.graphicsLayer {
                                    alpha = 1f - ramp(clock.floatValue, 1400f, 1560f)
                                }
                ) {
                    FlipWord(
                            word = "DEEPSEEK",
                            clock = clock,
                            startMs = 880f,
                            staggerMs = 36f,
                            fadeOutFrom = 1340f,
                            fadeOutTo = 1560f
                    )
                    FlipWord(
                            word = "HARNESS",
                            clock = clock,
                            startMs = 1340f,
                            staggerMs = 40f,
                            fadeOutFrom = Float.MAX_VALUE,
                            fadeOutTo = Float.MAX_VALUE,
                            hollow = true
                    )
                }
            }
        }
    }
}

private const val TOTAL_MS = 2000f

/**
 * 逐字翻转。
 *
 * [clock] 传 state 本身而不是值：传值会在组合阶段读它，每帧重组一次。
 */
@Composable
private fun FlipWord(
        word: String,
        clock: State<Float>,
        startMs: Float,
        staggerMs: Float,
        fadeOutFrom: Float,
        fadeOutTo: Float,
        /** true = 只描边不填充（镂空字）。 */
        hollow: Boolean = false
) {
    Row {
        word.forEachIndexed { index, ch ->
            Text(
                    text = ch.toString(),
                    style =
                            TextStyle(
                                    color = HermesTokens.Palette.text,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 3.sp,
                                    drawStyle = if (hollow) Stroke(width = 1.6f) else null
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

/** 缓入缓出（三次），用于飞行段：起步慢、中间快、落地稳。 */
private fun easeInOutCubic(x: Float): Float {
    return if (x < 0.5f) 4f * x * x * x
    else 1f - ((-2f * x + 2f) * (-2f * x + 2f) * (-2f * x + 2f)) / 2f
}
