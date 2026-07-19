package me.rerere.stapp.data.settings

import android.content.Context

/** Prompt 调试日志开关 — SharedPreferences 持久化，默认关闭 */
object PromptLogStore {
    private const val PREFS = "prompt_log"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
