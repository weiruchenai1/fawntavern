package me.rerere.fawntavern.ui.worldbook

import android.content.Context
import me.rerere.fawntavern.data.settings.WorldInfoSettingsStore
import me.rerere.fawntavern.data.worldbook.WorldInfoSettings

internal interface WorldInfoSettingsDataSource {
    fun load(): WorldInfoSettings
    fun save(settings: WorldInfoSettings)
}

internal class AndroidWorldInfoSettingsDataSource(
    private val context: Context,
) : WorldInfoSettingsDataSource {
    override fun load(): WorldInfoSettings = WorldInfoSettingsStore.get(context)
    override fun save(settings: WorldInfoSettings) = WorldInfoSettingsStore.set(context, settings)
}

internal class WorldInfoSettingsController(
    private val dataSource: WorldInfoSettingsDataSource,
) {
    fun load(): WorldInfoSettings = dataSource.load()

    fun update(settings: WorldInfoSettings): WorldInfoSettings {
        dataSource.save(settings)
        return settings
    }
}
