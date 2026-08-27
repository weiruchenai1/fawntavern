package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.data.settings.FontSizeStore
import me.rerere.fawntavern.data.settings.Preferences
import me.rerere.fawntavern.data.settings.PreferencesStore

internal class AndroidChatUiSettingsDataSource(
    private val context: Context,
) : ChatUiSettingsDataSource {
    override fun preferences(): Preferences = PreferencesStore.get(context)

    override fun fontScale(): Float = FontSizeStore.getScale(context)
}
