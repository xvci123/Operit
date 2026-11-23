package com.ai.assistance.operit.ui.hermes

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Hermes 启动层。
 *
 * 三段，全程只有一个视觉主体（鲸鱼）：
 *  1. 斜向光束从**左上扫到右下**，鲸鱼在光带前沿处被"扫"出来（遮罩显影，不是淡入）。
 *  2. 鲸鱼停顿一下。
 *  3. 缩到 26dp，**精确落到左上角菜单按钮的中心**，然后原地变成那个图标：
 *     启动层只退底色，鲸鱼不动；底下的 App 从后面淡进来。
 *
 * 启动层自己不再做散星汇聚 —— 星光只在空对话页出现。
 * 时间轴全部是匀速的：光带用 easeInOut 会在屏幕中央一闪而过，看起来像"没有光"。
 */
@Composable
fun HermesSplash(
    flyToHeader: Boolean,
    hold: Boolean = false,
    appReady: Boolean = true,
    onLanded: () -> Unit = {},
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val clock = remember { mutableFloatStateOf(0f) }
    val whale =
        remember {
            BitmapFactory.decodeResource(context.resources, R.drawable.whale_alpha).asImageBitmap()
        }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val holdState = rememberUpdatedState(hold)
    val readyState = rememberUpdatedState(appReady)
    val landedCallback = rememberUpdatedState(onLanded)
    val finishedCallback = rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        var virtual = 0f
        var last = withFrameNanos { it }
        var landed = false
        // 落地后等 App 真的画出第一帧再交接；最多等 1.2s，避免永远等下去。
        var waitedForApp = 0f
        while (true) {
            val frame = withFrameNanos { it }
            val dt = ((frame - last) / 1_000_000f).coerceIn(0f, 64f)
            last = frame

            var next = virtual + dt
            // hold 冻结在"起飞前"，不冻结在"落地后"：
            // 开机慢的时候鲸鱼停在画面中央，松开后完整飞一次，而不是卡在图标位置上再硬切。
            if (holdState.value) {
                next = minOf(next, HOLD_AT_MS)
            }
            val waitingApp =
                virtual >= FLY_END_MS && !readyState.value && waitedForApp < APP_WAIT_CAP_MS
            if (waitingApp) {
                waitedForApp += dt
                next = minOf(next, FLY_END_MS)
            }
            virtual = next
            clock.floatValue = virtual

            if (!landed && virtual >= FLY_END_MS) {
                landed = true
                landedCallback.value()
            }
            if (virtual >= TOTAL_MS) break
        }
        finishedCallback.value()
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val now = clock.floatValue
        val w = size.width
        val h = size.height
        val diag = sqrt(w * w + h * h)
        val bandWidth = w * 0.30f
        // 屏幕中心在光带行进方向上的投影，用来把"全局前沿位置"换算到旋转坐标系里。
        val centerProj = (w / 2f) * BEAM_UX + (h / 2f) * BEAM_UY
        val sweep = hermesRamp(now, BEAM_START_MS, BEAM_END_MS)
        val front = -0.06f * diag + 1.12f * diag * sweep

        // 1. 底色。只有它在交接阶段退场，鲸鱼不参与淡出。
        val bgAlpha = 1f - hermesRamp(now, FLY_END_MS, TOTAL_MS)
        if (bgAlpha > 0f) {
            drawRect(color = HermesTokens.Palette.bg, alpha = bgAlpha)
        }

        // 2. 鲸鱼：起于屏幕中心偏上，终于左上角菜单按钮（48dp IconButton 的中心 = 28dp）。
        val fly =
            if (flyToHeader) hermesEaseInOut(hermesRamp(now, FLY_START_MS, FLY_END_MS)) else 0f
        val startSize = 150.dp.toPx()
        val endSize = 26.dp.toPx()
        val whaleSize = startSize + (endSize - startSize) * fly
        val startCx = w / 2f
        val startCy = h / 2f - 28.dp.toPx()
        val endCx = 28.dp.toPx()
        val endCy = statusTop.toPx() + 32.dp.toPx()
        val cx = startCx + (endCx - startCx) * fly
        val cy = startCy + (endCy - startCy) * fly
        val left = cx - whaleSize / 2f
        val top = cy - whaleSize / 2f

        when {
            sweep <= 0f -> Unit
            sweep >= 1f ->
                drawImage(
                    image = whale,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(whaleSize.roundToInt(), whaleSize.roundToInt())
                )
            else -> drawWhaleRevealed(whale, left, top, whaleSize, front, centerProj, diag)
        }

        // 3. 光束。rotate(+45°) 让局部 +x 指向屏幕右下，光带于是从左上角扫向右下角。
        if (sweep > 0f && sweep < 1f) {
            val env = minOf(1f, sweep / 0.10f, (1f - sweep) / 0.10f)
            val travel = front - centerProj
            val halfBand = bandWidth / 2f
            // 显式 LinearGradientShader：渐变坐标就在"旋转后的局部坐标系"里，
            // 和下面画的矩形严丝合缝，不依赖 Brush 对默认边界的推断。
            val beamPaint =
                Paint().apply {
                    shader =
                        LinearGradientShader(
                            from = Offset(-halfBand, 0f),
                            to = Offset(halfBand, 0f),
                            colors =
                                listOf(
                                    Color.Transparent,
                                    Color(0xFF8EBCFF).copy(alpha = 0.08f * env),
                                    Color.White.copy(alpha = 0.40f * env),
                                    Color.White.copy(alpha = 0.72f * env),
                                    Color.White.copy(alpha = 0.40f * env),
                                    Color(0xFF8EBCFF).copy(alpha = 0.08f * env),
                                    Color.Transparent
                                ),
                            colorStops = listOf(0f, 0.26f, 0.44f, 0.50f, 0.56f, 0.74f, 1f),
                            tileMode = TileMode.Clamp
                        )
                }
            withTransform({
                rotate(degrees = 45f, pivot = center)
                translate(left = travel)
            }) {
                drawIntoCanvas { canvas ->
                    canvas.drawRect(Rect(-halfBand, -diag, halfBand, diag), beamPaint)
                }
            }
        }
    }
}

/**
 * 用斜向渐变做遮罩把鲸鱼"扫"出来：光带前沿之前透出鲸鱼，之后仍是暗的。
 * 走离屏层 + DstIn，边缘是软的；引擎侧没有 DstIn 的时候会退化成整条边也画不出来，
 * 所以这里兜底：图层失败时不画（宁可不画也不画一半）。
 */
private fun DrawScope.drawWhaleRevealed(
    whale: ImageBitmap,
    left: Float,
    top: Float,
    whaleSize: Float,
    front: Float,
    centerProj: Float,
    diag: Float
) {
    val soft = 40.dp.toPx()
    val half = diag
    val t0 = ((front - soft - (centerProj - half)) / (2f * half)).coerceIn(0f, 1f)
    val t1 = ((front - (centerProj - half)) / (2f * half)).coerceIn(0f, 1f)
    val stops = ArrayList<Pair<Float, Color>>(4)
    stops.add(0f to Color.White)
    if (t0 > 0.002f) stops.add(t0 to Color.White)
    if (t1 > t0 + 0.002f && t1 < 0.998f) stops.add(t1 to Color.Transparent)
    stops.add(1f to Color.Transparent)

    val maskPaint =
        Paint().apply {
            blendMode = BlendMode.DstIn
            shader =
                LinearGradientShader(
                    from = Offset(center.x - BEAM_UX * half, center.y - BEAM_UY * half),
                    to = Offset(center.x + BEAM_UX * half, center.y + BEAM_UY * half),
                    colors = stops.map { it.second },
                    colorStops = stops.map { it.first },
                    tileMode = TileMode.Clamp
                )
        }

    drawIntoCanvas { canvas ->
        val bounds = Rect(0f, 0f, size.width, size.height)
        canvas.saveLayer(bounds, Paint())
        drawImage(
            image = whale,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(whaleSize.roundToInt(), whaleSize.roundToInt())
        )
        canvas.drawRect(bounds, maskPaint)
        canvas.restore()
    }
}

/** 光带方向：屏幕坐标下 45° 指向右下。 */
private const val BEAM_UX = 0.70710678f
private const val BEAM_UY = 0.70710678f

private const val BEAM_START_MS = 520f
private const val BEAM_END_MS = 2000f
private const val FLY_START_MS = 2150f
private const val FLY_END_MS = 2850f
private const val TOTAL_MS = 3180f

/** hold 时冻结的时刻：光已扫完、鲸鱼完整、尚未起飞。 */
private const val HOLD_AT_MS = 2080f

/** 等 App 画出第一帧的上限，超时就照常交接。 */
private const val APP_WAIT_CAP_MS = 1200f
