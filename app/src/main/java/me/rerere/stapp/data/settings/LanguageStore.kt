package me.rerere.stapp.data.settings

import android.content.Context

/** 应用内语言设置 — SharedPreferences 持久化；切换语言重启 Activity 后经 pending 标记回到设置页 */
object LanguageStore {
    private const val PREFS = "language"
    private const val KEY_LANG = "lang"

    fun getLanguage(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "zh") ?: "zh"
    }

    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang).apply()
    }

    fun getLabel(lang: String): String = when (lang) {
        "zh" -> "中文(简体中文)"
        "en" -> "English"
        else -> lang
    }

    fun markPendingChange(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("pending", true).apply()
    }

    fun consumePendingChange(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val was = p.getBoolean("pending", false)
        if (was) p.edit().putBoolean("pending", false).apply()
        return was
    }
}
