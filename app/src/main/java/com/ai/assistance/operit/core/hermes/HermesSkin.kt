package com.ai.assistance.operit.core.hermes

import android.content.Context
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.util.AppLogger
import java.io.File

/**
 * Hermes 皮肤种子（P5 第一步）。
 *
 * 为什么是"种子"而不是另写一套主题：Operit 的主题是一整套偏好驱动的管线
 * （`OperitTheme` 读 `ThemePreferenceSnapshot`，还有自定义色、背景图、系统主题跟随），
 * 换掉它等于把 58 个屏的取色路径全部重测。所以只做一件事：**第一次启动时把偏好写成我们的黑蓝调**，
 * 之后用户仍然可以在设置里改回去。这条符合总文档"不动 OperitTheme 主体，用包裹层"的原则。
 *
 * 只用一次：`filesDir/hermes/skin_seeded` 存在就跳过。用户后来手动改了颜色不会被重新覆盖。
 */
object HermesSkin {

    private const val TAG = "HermesSkin"

    // 取自总文档 B.2 的黑蓝调色板
    const val BG = 0xFF070A12.toInt()
    const val SURFACE = 0xFF111826.toInt()
    const val PRIMARY = 0xFF4C8DFF.toInt()
    const val PRIMARY_DEEP = 0xFF1E3A6E.toInt()
    const val ACCENT = 0xFF7BD3FF.toInt()
    const val TEXT = 0xFFE8EEF7.toInt()
    const val TEXT_SECONDARY = 0xFF93A1B8.toInt()
    const val DANGER = 0xFFE5534B.toInt()
    const val SUCCESS = 0xFF4CB782.toInt()

    private fun marker(context: Context): File =
            File(File(context.filesDir, "hermes"), "skin_seeded")

    /** 幂等：需要写偏好时返回 true。调用方负责放到后台协程里（写偏好是 IO）。 */
    suspend fun seedIfNeeded(context: Context): Boolean {
        val appContext = context.applicationContext
        val marker = marker(appContext)
        if (marker.exists()) return false
        return try {
            val manager = ActivePromptManager.getInstance(appContext)
            val target = manager.getActivePrompt()
            manager.mutateActiveThemeForPrompt(target) { values ->
                values.withBoolean("use_system_theme", false)
                        .withString("theme_mode", "dark")
                        .withBoolean("use_custom_colors", true)
                        .withInt("custom_primary_color", PRIMARY)
                        .withInt("custom_secondary_color", PRIMARY_DEEP)
                        .withBoolean("use_background_image", false)
            }
            marker.parentFile?.mkdirs()
            marker.writeText(System.currentTimeMillis().toString())
            AppLogger.i(TAG, "Hermes 皮肤已写入（深色 + 黑蓝调）")
            true
        } catch (e: Exception) {
            // 写失败不影响启动，下次再来（marker 没写就不会跳过）
            AppLogger.w(TAG, "写入 Hermes 皮肤失败", e)
            false
        }
    }
}
