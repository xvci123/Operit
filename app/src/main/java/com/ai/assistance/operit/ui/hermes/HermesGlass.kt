package com.ai.assistance.operit.ui.hermes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/**
 * Hermes 假玻璃（P1-1）。
 *
 * ## 为什么不叫"玻璃"
 *
 * 真玻璃 = 背景模糊。Compose 的 `Modifier.blur` / `RenderEffect` 要 **API 31**，
 * 本项目自带的 liquidGlass / waterGlass 更是 gate 在 **API 33**；这台机器是 Android 11（API 30）。
 * 所以这里做的是**观感替代**，三层叠出来：
 *
 * 1. 外阴影（原生投影，不需要模糊能力）
 * 2. 半透明白色渐变填充（上亮下暗，模拟受光面）
 * 3. 1dp 渐变描边（上亮下更淡，模拟边缘高光）
 *
 * 将来要换成真玻璃，只改这个文件内部实现，**调用方一行都不用动**——这是刻意做的隔离。
 */

/** 玻璃形状：默认主圆角。 */
fun hermesGlassShape(radius: Dp = HermesTokens.Radius.md): Shape = RoundedCornerShape(radius)

/**
 * 给任意容器套一层假玻璃。
 *
 * 顺序很重要：shadow → clip → background → border。
 * 先 clip 再画底和边，圆角才不会被描边切出直角。
 */
fun Modifier.hermesGlass(
        shape: Shape = hermesGlassShape(),
        elevation: Dp = HermesTokens.Stroke.cardElevation
): Modifier =
        this
                .shadow(
                        elevation = elevation,
                        shape = shape,
                        clip = false,
                        ambientColor = Color.Black,
                        spotColor = Color.Black
                )
                .clip(shape)
                .background(
                        Brush.verticalGradient(
                                listOf(
                                        HermesTokens.Palette.glassFillTop,
                                        HermesTokens.Palette.glassFillBottom
                                )
                        )
                )
                .border(
                        width = HermesTokens.Stroke.thin,
                        brush =
                                Brush.verticalGradient(
                                        listOf(
                                                HermesTokens.Palette.glassStrokeTop,
                                                HermesTokens.Palette.glassStrokeBottom
                                        )
                                ),
                        shape = shape
                )

/**
 * 玻璃卡片。
 *
 * 用法：`HermesGlassCard { ... }`，内容按 Column 排列。
 * 不要自己再叠一层 background，否则会盖掉高光。
 */
@Composable
fun HermesGlassCard(
        modifier: Modifier = Modifier,
        radius: Dp = HermesTokens.Radius.md,
        elevation: Dp = HermesTokens.Stroke.cardElevation,
        contentPadding: Dp = HermesTokens.Spacing.base,
        content: @Composable ColumnScope.() -> Unit
) {
    Column(
            modifier =
                    modifier.hermesGlass(shape = hermesGlassShape(radius), elevation = elevation)
                            .padding(contentPadding),
            content = content
    )
}
