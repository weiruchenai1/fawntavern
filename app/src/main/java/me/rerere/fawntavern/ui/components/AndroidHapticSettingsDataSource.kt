package me.rerere.fawntavern.ui.components

import android.content.Context
import me.rerere.fawntavern.data.settings.PreferencesStore

internal class AndroidHapticSettingsDataSource(
    private val context: Context,
) : HapticSettingsDataSource {
    override fun longPressEnabled(): Boolean = PreferencesStore.get(context).longPressHaptic
}
