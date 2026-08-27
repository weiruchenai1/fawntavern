package me.rerere.fawntavern.data.settings

import androidx.core.content.edit

import android.content.Context

/** 全局字体缩放 — SharedPreferences 持久化 */
object FontSizeStore {
    private const val PREFS = "font_size"
    private const val KEY_SCALE = "scale"

    fun getScale(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_SCALE, 1.0f)

    fun setScale(context: Context, scale: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putFloat(KEY_SCALE, scale) }
    }
}
