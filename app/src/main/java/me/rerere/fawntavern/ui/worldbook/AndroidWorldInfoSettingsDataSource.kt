package me.rerere.fawntavern.ui.worldbook

import android.content.Context
import me.rerere.fawntavern.data.settings.WorldInfoSettingsStore
import me.rerere.fawntavern.data.worldbook.WorldInfoSettings

internal class AndroidWorldInfoSettingsDataSource(
    private val context: Context,
) : WorldInfoSettingsDataSource {
    override fun load(): WorldInfoSettings = WorldInfoSettingsStore.get(context)
    override fun save(settings: WorldInfoSettings) = WorldInfoSettingsStore.set(context, settings)
}
