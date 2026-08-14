package me.rerere.fawntavern.ui.worldbook

import me.rerere.fawntavern.data.worldbook.WorldInfoSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldInfoSettingsControllerTest {
    @Test
    fun updatePersistsAndReturnsSettings() {
        val source = FakeWorldInfoSettingsDataSource()
        val controller = WorldInfoSettingsController(source)
        val updated = WorldInfoSettings(scanDepth = 8, recursive = false)

        assertEquals(WorldInfoSettings(), controller.load())
        assertEquals(updated, controller.update(updated))
        assertEquals(updated, source.settings)
    }

    private class FakeWorldInfoSettingsDataSource : WorldInfoSettingsDataSource {
        var settings = WorldInfoSettings()
        override fun load(): WorldInfoSettings = settings
        override fun save(settings: WorldInfoSettings) { this.settings = settings }
    }
}
