package me.rerere.fawntavern.data.settings

import androidx.core.content.edit
import me.rerere.fawntavern.data.commitChanges

import android.content.Context

/** 用户资料（用户名/头像）— SharedPreferences 持久化 */
object UserProfileStore {
    private const val PREFS = "user_profile"
    private const val KEY_NAME = "name"
    private const val KEY_AVATAR_COLOR = "avatar_color"
    private const val KEY_AVATAR_PATH = "avatar_path"
    private const val KEY_DESCRIPTION = "description"

    private const val DEFAULT_AVATAR_COLOR = 0xFF34A85300000000UL

    fun getName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null) ?: "user"

    fun setName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_NAME, name.trim()) }
    }

    fun getAvatarColor(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_AVATAR_COLOR, DEFAULT_AVATAR_COLOR.toLong())

    fun setAvatarColor(context: Context, color: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putLong(KEY_AVATAR_COLOR, color) }
    }

    fun getAvatarPath(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AVATAR_PATH, null)

    fun setAvatarPath(context: Context, path: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_AVATAR_PATH, path) }
    }

    fun setAvatarPathSync(context: Context, path: String?) {
        check(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .commitChanges { putString(KEY_AVATAR_PATH, path) }
        ) { "Unable to persist avatar path" }
    }

    fun getDescription(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DESCRIPTION, null) ?: ""

    fun setDescription(context: Context, description: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_DESCRIPTION, description.trim()) }
    }
}
