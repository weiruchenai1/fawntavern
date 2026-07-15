package me.rerere.stapp.data.settings

import android.content.Context
import androidx.compose.ui.graphics.Color

/** 用户资料（用户名/头像）— SharedPreferences 持久化 */
object UserProfileStore {
    private const val PREFS = "user_profile"
    private const val KEY_NAME = "name"
    private const val KEY_AVATAR_COLOR = "avatar_color"
    private const val KEY_AVATAR_PATH = "avatar_path"

    private val DEFAULT_AVATAR_COLOR = Color(0xFF34A853)

    fun getName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null) ?: "user"

    fun setName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_NAME, name.trim()).apply()
    }

    fun getAvatarColor(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_AVATAR_COLOR, DEFAULT_AVATAR_COLOR.value.toLong())

    fun setAvatarColor(context: Context, color: Color) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_AVATAR_COLOR, color.value.toLong()).apply()
    }

    fun getAvatarPath(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AVATAR_PATH, null)

    fun setAvatarPath(context: Context, path: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_AVATAR_PATH, path).apply()
    }
}
