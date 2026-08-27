package me.rerere.fawntavern.ui.worldbook

import me.rerere.fawntavern.data.worldbook.WorldInfoSettings

interface WorldInfoSettingsDataSource {
    fun load(): WorldInfoSettings
    fun save(settings: WorldInfoSettings)
}

class WorldInfoSettingsController(
    private val dataSource: WorldInfoSettingsDataSource,
) {
    fun load(): WorldInfoSettings = dataSource.load()

    fun update(settings: WorldInfoSettings): WorldInfoSettings {
        dataSource.save(settings)
        return settings
    }
}
