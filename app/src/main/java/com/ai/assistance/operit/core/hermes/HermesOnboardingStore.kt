package com.ai.assistance.operit.core.hermes

import android.content.Context

/**
 * 引导是否已完成。独立于协议/权限偏好的临时页面状态，杀掉进程再进来也能读到。
 * 只记录一个布尔值，不碰 Room。
 */
object HermesOnboardingStore {
    private const val PREFS = "hermes_onboarding"
    private const val KEY_DONE = "done"

    fun isDone(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DONE, true)
            .apply()
    }
}
