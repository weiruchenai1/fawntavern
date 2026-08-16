package me.rerere.fawntavern.data.settings

import android.content.Context
import androidx.core.content.edit

object AppStatisticsStore {
    private const val PREFS = "app_statistics"
    private const val KEY_LAUNCH_COUNT = "launch_count"

    fun incrementLaunchCount(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.edit {
            putInt(KEY_LAUNCH_COUNT, preferences.getInt(KEY_LAUNCH_COUNT, 0) + 1)
        }
    }

    fun launchCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LAUNCH_COUNT, 0)
}
