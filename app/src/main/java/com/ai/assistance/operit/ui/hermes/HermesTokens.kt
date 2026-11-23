package com.ai.assistance.operit.ui.hermes

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hermes 设计 token（唯一真源）。
 *
 * 规矩：**所有皮肤相关的颜色、圆角、间距、动效只能从这里取**，不许在页面里另写字面量。
 * 一代原来最大的问题就是各屏各写 `RoundedCornerShape(8.dp)`（全项目 500+ 处），
 * 想整体调一档都没有着力点。
 *
 * 取值依据：总文档附录 B.2（配色）与《Hermes-换皮方案-V1.md》§3。
 *
 * 平台限制记在这里，免得后来人重复踩：
 *  - 本机是 Android 11（API 30）。Compose 的 `Modifier.blur` / `RenderEffect` 要 API 31，
 *    项目自带的 liquidGlass / waterGlass 更是 gate 在 API 33。
 *    所以"玻璃"只能是**假玻璃**：半透明填充 + 亮描边 + 顶部高光 + 外阴影，
 *    不存在"透过它看见背后内容"的效果。
 */
object HermesTokens {

    /**
     * 配色。
     *
     * 取值**不是自己编的**：直接对齐 Operit2 的 Flutter 主题源码
     * `apps/flutter/app/lib/ui/theme/OperitTheme.dart`（已下载到 `/root/apkwork/operit2ref/`），
     * 里面实测到的暗色底是 #0D0E10 / #121212 / #131314 / #161616 / #18181A / #1B1C1E / #262628，
     * 强调蓝是 #3B82F6，次要文字 #8F929A / #A0A1A7，弱化文字 #4C4D53 / #5A5C63，主文字 #E8EAEF。
     *
     * 与 `HermesSkin` 保持同值（那边是写进偏好种子的 Int，这边是 Compose Color）。
     */
    object Palette {
        val bg = Color(0xFF15171B)
        val bgGlow = Color(0xFF1A1D22)
        val surface = Color(0xFF1E2127)
        val surfaceHigh = Color(0xFF282C34)
        val outline = Color(0xFF3A3F4A)
        val text = Color(0xFFF0F2F6)
        val textSecondary = Color(0xFFA6ACB8)
        val textMuted = Color(0xFF78808F)
        val primary = Color(0xFF4C8DFF)
        val primaryDeep = Color(0xFF1E3A6E)
        val accent = Color(0xFF7BD3FF)
        val danger = Color(0xFFE5534B)
        val success = Color(0xFF4CB782)

        /** 假玻璃填充（叠在 surface 之上的那层白，非常淡）。 */
        val glassFillTop = Color.White.copy(alpha = 0.075f)
        val glassFillBottom = Color.White.copy(alpha = 0.035f)

        /** 假玻璃描边：上亮下暗，制造"受光"的边。 */
        val glassStrokeTop = Color.White.copy(alpha = 0.16f)
        val glassStrokeBottom = Color.White.copy(alpha = 0.05f)

        /** 按压时的白色罩层。 */
        val pressOverlay = Color.White.copy(alpha = 0.06f)
    }

    /** 圆角。主圆角 16dp（一代以 4/6/8dp 为主，整体抬一档）。 */
    object Radius {
        val xs: Dp = 8.dp
        val sm: Dp = 12.dp
        val md: Dp = 16.dp
        val lg: Dp = 24.dp
        val xl: Dp = 28.dp
        val full: Dp = 999.dp
    }

    /** 间距。一代混用 2/6/10/14，统一到这套 4 的倍数。 */
    object Spacing {
        val xxs: Dp = 2.dp
        val xs: Dp = 4.dp
        val sm: Dp = 8.dp
        val md: Dp = 12.dp
        val base: Dp = 16.dp
        val lg: Dp = 20.dp
        val xl: Dp = 24.dp
        val xxl: Dp = 32.dp
    }

    /** 描边与阴影。 */
    object Stroke {
        val thin: Dp = 1.dp
        val hairline: Dp = 0.5.dp

        /** 假玻璃外阴影强度。真模糊做不到，用原生投影拉开层次。 */
        val cardElevation: Dp = 10.dp
        val sheetElevation: Dp = 18.dp
    }

    /** 动效。 */
    object Motion {
        val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val decelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

        const val DUR_FAST = 120
        const val DUR_BASE = 220
        const val DUR_SLOW = 320

        /** 卡片按压缩放。 */
        const val PRESS_SCALE = 0.97f
    }
}
