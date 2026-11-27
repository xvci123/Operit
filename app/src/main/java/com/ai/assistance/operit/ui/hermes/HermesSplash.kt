package com.ai.assistance.operit.ui.hermes

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Hermes 启动层。
 *
 * 四段，全程只有一个视觉主体（鲸鱼）：
 *  1. 斜向光束从**左上扫到右下**（速度走 cubic-bezier(.3,.6,.3,1)，不是匀速，
 *     也不是 easeInOut —— 匀速没有加速感，easeInOut 会在屏幕中央一闪而过）。
 *     鲸鱼在光带前沿处被"扫"出来。
 *  2. 品牌字：`DEEPSEEK` 实心白逐字翻转出现 → 原地翻转消失 → 同一位置 `HARNESS`
 *     镂空描边逐字出现 → 整体淡出。
 *  3. 鲸鱼缩到 26dp，**精确落到左上角菜单按钮的中心**，在那里停住。
 *  4. 启动层只退底色，鲸鱼原地不动；底下的 App 从后面淡进来，图标正好接住鲸鱼。
 *
 * 三条硬约束（都踩过坑）：
 *  - **时间来自墙钟**（`SystemClock.elapsedRealtime()` + Activity 级起点），
 *    不靠 `LaunchedEffect` 里的累加。`setContent` 被再次调用会重建整棵树、
 *    `LaunchedEffect` 会重开，累加式时钟会从 0 重新播一遍 —— 真机上表现就是
 *    "鲸鱼不飞"或者"动画又播了一次"。
 *  - 显影用**路径裁剪**（`clipPath` + 多层半透明），不用 `saveLayer` + `BlendMode.DstIn`。
 *    离屏层在真机的硬件画布上和浏览器 canvas 不一致，同一个公式实测观感不同。
 *  - 启动层不放散星汇聚；星光只出现在空对话页，而且只在进程内播一次。
 *
 * 时间轴（毫秒，墙钟）：
 * ```
 *   0    底色 + 鲸鱼淡入
 *   520  光束起   1900 光束止（减速收尾）
 *   620  DEEPSEEK 逐字翻转 1240 齐
 *   1250 DEEPSEEK 逐字翻走 1390 空
 *   1400 HARNESS  逐字翻转 1950 齐
 *   1950 品牌字淡出 2130 全无
 *   2150 起飞     2900 落到图标（26dp）
 *   2900 底色退场 3400 交接完成（鲸鱼一直停在图标上，不淡出）
 * ```
 */
@Composable
fun HermesSplash(
    /**
     * 目的地是否真的存在（引导/权限没做完时聊天页顶栏还没有）。
     * 现在**只在日志里体现**：即便目的地还不存在，鲸鱼也照飞 ——
     * 用户看到的必须是"飞过去"，而不是"停在中场然后消失"。
     */
    flyToHeader: Boolean,
    hold: Boolean = false,
    appReady: Boolean = true,
    onLoading: () -> Unit = {},
    onLanded: () -> Unit = {},
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val clock = remember { mutableFloatStateOf(0f) }
    val whale =
        remember {
            BitmapFactory.decodeResource(context.resources, R.drawable.whale_alpha).asImageBitmap()
        }
    val measurer = rememberTextMeasurer()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val holdState = rememberUpdatedState(hold)
    val readyState = rememberUpdatedState(appReady)
    val loadingCallback = rememberUpdatedState(onLoading)
    val landedCallback = rememberUpdatedState(onLanded)
    val finishedCallback = rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        // 只用 delay 驱动，不用 withFrameNanos：delay 一定会恢复，
        // 不依赖"有没有下一帧"。帧时钟一旦卡住，这里的 withFrameNanos 就永远不返回，
        // 表现是黑屏且日志一行都没有。
        var virtual = hermesSplashConsumedMs.coerceIn(0f, TOTAL_MS)
        var lastWall = SystemClock.elapsedRealtime()
        var landed = false
        var loadingNotified = false
        var waitedForApp = 0f
        var lastPhase = -1
        Log.i("HermesSplash", "start resumeAt=${virtual.toInt()}ms hold=${holdState.value}")

        while (true) {
            // 帧同步：有帧就跟帧（60/120Hz 都贴合），没有帧就靠超时兜底继续推进 ——
            // 既拿到丝滑的光带，又保留"帧时钟不推也不会卡死"的安全性。
            withTimeoutOrNull(34L) { withFrameNanos { } }
            val wall = SystemClock.elapsedRealtime()
            val dt = (wall - lastWall).coerceIn(0L, 64L).toFloat()
            lastWall = wall

            var next = virtual + dt
            // hold：冻结在"光已扫完、字已淡出、鲸鱼完整但还没起飞"
            if (holdState.value) next = minOf(next, HOLD_AT_MS)
            // 已落地但 App 还没出第一帧：停在图标位置等，最多 1.2s
            // 加载阶段：文字退完后停在 LAODING 点，等主界面画出来（最多 8 秒）才起飞
            if (next >= LOADING_AT_MS && !readyState.value && waitedForApp < APP_WAIT_CAP_MS) {
                waitedForApp += dt
                next = minOf(next, LOADING_AT_MS)
            }
            virtual = next
            hermesSplashConsumedMs = virtual
            clock.floatValue = virtual

            val phase =
                when {
                    virtual < BEAM_START_MS -> 0
                    virtual < BEAM_END_MS -> 1
                    virtual < FLY_START_MS -> 2
                    virtual < FLY_END_MS -> 3
                    else -> 4
                }
            if (phase != lastPhase) {
                lastPhase = phase
                Log.i(
                    "HermesSplash",
                    "phase=$phase t=${virtual.toInt()}ms flyToHeader=$flyToHeader hold=${holdState.value}"
                )
            }

            if (!loadingNotified && virtual >= LOADING_AT_MS) {
                loadingNotified = true
                loadingCallback.value()   // 主界面开始组合，加载环同时转起来
            }
            if (!landed && virtual >= FLY_END_MS) {
                landed = true
                landedCallback.value()
            }
            if (virtual >= TOTAL_MS) break
        }
        Log.i("HermesSplash", "finished at ${hermesSplashConsumedMs.toInt()}ms")
        finishedCallback.value()
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val now = clock.floatValue
        val w = size.width
        val h = size.height
        val diag = sqrt(w * w + h * h)

        // ---- 光带前沿（全局坐标，沿 (BEAM_UX, BEAM_UY) 方向的投影距离）----
        val sweepRaw = hermesRamp(now, BEAM_START_MS, BEAM_END_MS)
        // 速度曲线：起步就有速度、收尾减速。匀速像"白条平移"，easeInOut 像"闪一下"。
        val sweep = hermesBeamCurve(sweepRaw)
        val front = -0.10f * diag + 1.20f * diag * sweep
        // 屏幕中心在本方向上的投影：把"全局前沿"换算进旋转后的局部坐标。
        val centerProj = (w / 2f) * BEAM_UX + (h / 2f) * BEAM_UY

        // ---- 1. 底色（只有它退场，鲸鱼不参与淡出）----
        val bgAlpha = 1f - hermesRamp(now, FLY_END_MS, TOTAL_MS)
        if (bgAlpha > 0f) {
            drawRect(color = HermesTokens.Palette.bg, alpha = bgAlpha)
        }

        // ---- 2. 鲸鱼位置：屏幕中心偏上 → 左上角菜单按钮中心 ----
        val startSize = 150.dp.toPx()
        val endSize = 26.dp.toPx()
        val startCx = w / 2f
        val startCy = h / 2f - 28.dp.toPx()
        val endCx = 28.dp.toPx()
        val endCy = statusTop.toPx() + 32.dp.toPx()

        // ---- 3. 品牌字（鲸鱼下方，飞行前已淡出）----
        if (now >= WORD_A_IN_MS && now < WORD_OUT_END_MS) {
            drawWordmark(
                measurer = measurer,
                now = now,
                cx = w / 2f,
                top = startCy + startSize / 2f + 22.dp.toPx()
            )
        }

        // ---- 3.5 加载环：鲸鱼停在中间等主界面时，下方转一个圈 ----
        if (now >= LOADING_AT_MS && !readyState.value) {
            val ring = 13.dp.toPx()
            val ringCx = w / 2f
            val ringCy = startCy + startSize / 2f + 30.dp.toPx()
            val spin = (now / 900f) * 360f
            withTransform({ rotate(degrees = spin, pivot = Offset(ringCx, ringCy)) }) {
                drawArc(
                    color = Color.White.copy(alpha = 0.72f),
                    startAngle = 0f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(ringCx - ring, ringCy - ring),
                    size = Size(ring * 2f, ring * 2f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // ---- 4. 鲸鱼：显影 → 完整 → 飞行 ----
        if (now >= WHALE_IN_MS) {
            val fly = hermesEaseInOut(hermesRamp(now, FLY_START_MS, FLY_END_MS))
            val whaleSize = startSize + (endSize - startSize) * fly
            val cx = startCx + (endCx - startCx) * fly
            val cy = startCy + (endCy - startCy) * fly
            val left = cx - whaleSize / 2f
            val top = cy - whaleSize / 2f
            val appear = hermesRamp(now, WHALE_IN_MS, WHALE_IN_MS + 320f)
            when {
                sweepRaw >= 1f ->
                    drawImage(
                        image = whale,
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(whaleSize.roundToInt(), whaleSize.roundToInt()),
                        alpha = appear
                    )
                sweepRaw <= 0f -> Unit // 光还没出发：鲸鱼完全在暗处
                else ->
                    drawWhaleRevealed(
                        whale = whale,
                        left = left,
                        top = top,
                        whaleSize = whaleSize,
                        front = front,
                        centerProj = centerProj,
                        diag = diag,
                        alpha = appear
                    )
            }
        }

        // ---- 5. 光束（画在鲸鱼之上：光是打过来的，不是垫在下面）----
        if (sweepRaw > 0f && sweepRaw < 1f) {
            val env = minOf(1f, sweepRaw / 0.10f, (1f - sweepRaw) / 0.10f)
            if (env > 0.004f) {
                val bandWidth = w * 0.45f
                val travel = front - centerProj
                val halfBand = bandWidth / 2f
                // 显式 LinearGradientShader：渐变坐标就在旋转后的局部坐标系里，
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
                                        Color(0xFF8EBCFF).copy(alpha = 0.13f * env),
                                        Color.White.copy(alpha = 0.46f * env),
                                        Color.White.copy(alpha = 0.72f * env),
                                        Color.White.copy(alpha = 0.46f * env),
                                        Color(0xFF8EBCFF).copy(alpha = 0.13f * env),
                                        Color.Transparent
                                    ),
                                colorStops = listOf(0f, 0.28f, 0.42f, 0.50f, 0.58f, 0.72f, 1f),
                                tileMode = TileMode.Clamp
                            )
                    }
                // rotate(+45°) 让局部 +x 指向屏幕右下：光带于是从左上角扫向右下角。
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
}

/**
 * 用**路径裁剪**把鲸鱼"扫"出来：光带前沿之前的部分可见，之后仍是暗的，中间三段渐次。
 *
 * 之前的实现是 `saveLayer` + `BlendMode.DstIn` + 渐变遮罩，公式和浏览器预览一致，
 * 但在真机硬件画布上做出来的观感完全不同（离屏层合成/抖动不一致）。裁剪是纯几何，
 * 各设备一致；软边用三段叠加近似，肉眼几乎看不出是台阶。
 */
private fun DrawScope.drawWhaleRevealed(
    whale: ImageBitmap,
    left: Float,
    top: Float,
    whaleSize: Float,
    front: Float,
    centerProj: Float,
    diag: Float,
    alpha: Float
) {
    val soft = 46.dp.toPx()
    val dstOffset = IntOffset(left.roundToInt(), top.roundToInt())
    val dstSize = IntSize(whaleSize.roundToInt(), whaleSize.roundToInt())

    fun pass(distance: Float, a: Float) {
        if (a <= 0.001f) return
        clipPath(sweptHalfPlane(distance, centerProj, diag)) {
            drawImage(image = whale, dstOffset = dstOffset, dstSize = dstSize, alpha = a * alpha)
        }
    }

    // 已扫过的主体（硬边）→ 前沿后面三段软过渡
    pass(front - soft, 1f)
    pass(front - soft * 0.66f, 0.55f)
    pass(front - soft * 0.33f, 0.40f)
    pass(front, 0.28f)
}

/** 沿光带方向、位于 [distance] 之前的半平面（一个巨大的四边形，够盖住整块画布）。 */
private fun DrawScope.sweptHalfPlane(distance: Float, centerProj: Float, diag: Float): Path {
    val l = diag * 2.4f
    val ex = -BEAM_UY
    val ey = BEAM_UX
    // 局部坐标 → 全局：沿方向的位移 distance 换成"离屏幕中心的距离"
    val d = distance - centerProj
    val px = center.x + BEAM_UX * d
    val py = center.y + BEAM_UY * d
    return Path().apply {
        moveTo(px + ex * l, py + ey * l)
        lineTo(px - ex * l, py - ey * l)
        lineTo(px - ex * l - BEAM_UX * 2f * l, py - ey * l - BEAM_UY * 2f * l)
        lineTo(px + ex * l - BEAM_UX * 2f * l, py + ey * l - BEAM_UY * 2f * l)
        close()
    }
}

/**
 * 品牌字：`DEEPSEEK`（实心白）逐字翻转出现 → 逐字翻走 → `HARNESS`（镂空描边）
 * 在**同一个位置**逐字出现 → 整词淡出。
 *
 * 翻转用 X 轴压缩模拟（`scale(scaleX, 1f)`），观感是"积木翻面"，
 * 和文档 B.7.5 写的 `rotationY 90°→0°` 等价；Compose 的 Canvas 没有 3D 变换，
 * 压缩是唯一不需要 graphicsLayer 就能逐字控制的做法。
 */
private fun DrawScope.drawWordmark(
    measurer: androidx.compose.ui.text.TextMeasurer,
    now: Float,
    cx: Float,
    top: Float
) {
    val wordB = now >= WORD_B_IN_MS
    val word = if (wordB) WORD_B else WORD_A
    val style =
        TextStyle(
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = if (wordB) Color.White else WORD_SOLID_OUT
        )

    // 逐字测量后再整体居中：比例字体按整串测会算不出每个字母的落点。
    val layouts = word.map { measurer.measure(it.toString(), style) }
    val letterGap = 1.2.dp.toPx()
    var total = 0f
    layouts.forEachIndexed { i, l ->
        total += l.size.width.toFloat()
        if (i != layouts.lastIndex) total += letterGap
    }
    var x = cx - total / 2f

    val outAlpha = 1f - hermesRamp(now, WORD_OUT_START_MS, WORD_OUT_END_MS)
    if (outAlpha <= 0.004f) return

    // 逐字的翻转进度：每个字母比前一个晚 STAGGER，单个字母用 0.17s 翻完（对齐 boot.html）。
    val stagger = if (wordB) WORD_B_STAGGER_MS else WORD_A_STAGGER_MS
    val t0 = if (wordB) WORD_B_IN_MS else WORD_A_IN_MS

    layouts.forEachIndexed { index, layout ->
        val lw = layout.size.width.toFloat()
        val lh = layout.size.height.toFloat()
        val p = hermesRamp(now, t0 + stagger * index, t0 + stagger * index + WORD_FLIP_MS)
        // 翻走：DEEPSEEK 整串同时退场（不逐字，否则末尾几个字母还没翻完就被
        // HARNESS 顶掉，看上去是"闪一下"）。窗口 1250→1380，下一词 1400 进场。
        val q = if (!wordB) hermesRamp(now, WORD_A_OUT_MS, WORD_B_IN_MS - 20f) else 0f
        val scaleX = (hermesEaseOut(p) * (1f - q * q * q)).coerceAtLeast(0.02f)
        val a = (p.coerceAtMost(1f) * (1f - q)) * outAlpha
        if (a > 0.004f && p > 0f) {
            withTransform({
                scale(scaleX = scaleX, scaleY = 1f, pivot = Offset(x + lw / 2f, top + lh / 2f))
            }) {
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(x, top),
                    alpha = a,
                    color = if (wordB) Color.White else WORD_SOLID_OUT,
                    // HARNESS 镂空：只描边不填充
                    drawStyle = null
                )
            }
        }
        x += lw + letterGap
    }
}

/**
 * 光带速度曲线：`cubic-bezier(.3,.6,.3,1)` —— 起步就动、收尾减速。
 *
 * 为什么不是匀速：匀速的白条像"一块板平移过去"，没有"光扫过来"的感觉。
 * 为什么不是 easeInOut：它在屏幕中央的速度是匀速的 2 倍，光带 1.4 秒里
 * 有 0.2 秒就跨过中心，看起来像"没扫"。
 */
private fun hermesBeamCurve(x: Float): Float {
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    fun fx(t: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * 0.3f + 3f * u * t * t * 0.3f + t * t * t
    }
    fun fy(t: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * 0.6f + 3f * u * t * t * 1f + t * t * t
    }
    var lo = 0f
    var hi = 1f
    var t = x
    repeat(18) {
        if (fx(t) < x) lo = t else hi = t
        t = (lo + hi) / 2f
    }
    return fy(t)
}

/**
 * 进程内"已经播掉的毫秒数"。
 *
 * 这是启动动画唯一的状态。**不能**把起点锚在 `onCreate`：从 `onCreate` 到真正画出
 * 第一帧之间隔着重初始化（插件、数据库、MCP…），实测能到 3 秒以上 ——
 * 锚在 `onCreate` 的话，动画一启动就已经"播完了"，屏幕只剩底色，看起来就是黑屏。
 * 所以起点是第一帧，靠这个变量保证"重新 setContent 只接着播、不重播"。
 */
private var hermesSplashConsumedMs = 0f

/** 光带方向：屏幕坐标下 45° 指向右下。 */
private const val BEAM_UX = 0.70710678f
private const val BEAM_UY = 0.70710678f

private val WORD_SOLID_OUT = Color(0xFFF2F6FF)

private val WORD_A = "DEEPSEEK"
private val WORD_B = "HARNESS"

private const val WHALE_IN_MS = 120f
private const val BEAM_START_MS = 520f
private const val BEAM_END_MS = 2020f

private const val WORD_A_IN_MS = 560f
private const val WORD_A_STAGGER_MS = 62f
private const val WORD_A_OUT_MS = 1250f
private const val WORD_B_IN_MS = 1400f
private const val WORD_B_STAGGER_MS = 46f
private const val WORD_FLIP_MS = 170f
private const val WORD_OUT_START_MS = 2000f
private const val WORD_OUT_END_MS = 2180f

private const val FLY_START_MS = 2250f
private const val FLY_END_MS = 2950f
/** 落地后停在图标上 0.5 秒再交接：这是"鲸鱼变成图标"被看见的唯一窗口。 */
private const val TOTAL_MS = 3300f

/** hold 时冻结的时刻：光已扫完、字已淡出、鲸鱼完整，尚未起飞。 */
private const val HOLD_AT_MS = 2200f

/** 进入"加载阶段"的时刻：文字退完、鲸鱼停在中间等主界面。 */
private const val LOADING_AT_MS = 2180f

/** 等 App 画出第一帧的上限，超时就照常交接。 */
private const val APP_WAIT_CAP_MS = 8000L
