package me.rerere.fawntavern.ui.components

import android.content.Context
import me.rerere.fawntavern.data.settings.PreferencesStore

internal fun interface HapticSettingsDataSource {
    fun longPressEnabled(): Boolean
}

internal class AndroidHapticSettingsDataSource(
    private val context: Context,
) : HapticSettingsDataSource {
    override fun longPressEnabled(): Boolean = PreferencesStore.get(context).longPressHaptic
}

internal class HapticSettingsController(
    private val dataSource: HapticSettingsDataSource,
) {
    fun longPressEnabled(): Boolean = dataSource.longPressEnabled()
}
