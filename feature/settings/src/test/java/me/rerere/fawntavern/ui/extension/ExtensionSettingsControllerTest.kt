package me.rerere.fawntavern.ui.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionSettingsControllerTest {
    @Test
    fun readsAndWritesExtensionSettings() {
        val source = FakeExtensionSettingsDataSource()
        val controller = ExtensionSettingsController(source)

        assertFalse(controller.isEnabled("summary"))
        assertTrue(controller.setEnabled("summary", true))
        controller.setConfig("summary", "config")

        assertTrue(controller.isEnabled("summary"))
        assertEquals("config", controller.config("summary"))
    }

    private class FakeExtensionSettingsDataSource : ExtensionSettingsDataSource {
        private val enabled = mutableMapOf<String, Boolean>()
        private val configs = mutableMapOf<String, String>()

        override fun isEnabled(id: String): Boolean = enabled[id] == true
        override fun setEnabled(id: String, enabled: Boolean) { this.enabled[id] = enabled }
        override fun config(id: String): String = configs[id].orEmpty()
        override fun setConfig(id: String, config: String) { configs[id] = config }
    }
}
