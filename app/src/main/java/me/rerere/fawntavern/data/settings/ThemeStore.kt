package me.rerere.fawntavern.data.settings

import androidx.core.content.edit

import android.content.Context

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 浅色/深色模式 — SharedPreferences 持久化 */
object ThemeStore {
    private const val PREFS = "theme"
    private const val KEY_MODE = "mode"

    fun getMode(context: Context): ThemeMode {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, "SYSTEM") ?: "SYSTEM"
        return try { ThemeMode.valueOf(name) } catch (_: Exception) { ThemeMode.SYSTEM }
    }

    fun setMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_MODE, mode.name) }
    }
}
