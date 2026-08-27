package me.rerere.fawntavern.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWebSearchSettingsControllerTest {
    @Test
    fun reloadClampsSelectionAfterServiceRemoval() {
        val source = FakeWebSearchSettingsDataSource().apply {
            selected = 1
            values = listOf(service("first"), service("second"))
        }
        val controller = ChatWebSearchSettingsController(source)

        val initial = controller.load()
        assertEquals("second", initial.services[initial.selectedIndex].id)

        source.values = listOf(service("first"))
        val refreshed = controller.load()

        assertEquals(0, refreshed.selectedIndex)
        assertEquals("first", refreshed.services.single().id)
    }

    @Test
    fun toggleAndSelectPersistTheNormalizedValues() {
        val source = FakeWebSearchSettingsDataSource().apply {
            values = listOf(service("first"), service("second"))
        }
        val controller = ChatWebSearchSettingsController(source)
        val initial = controller.load()

        val enabled = controller.toggle(initial)
        val selected = controller.select(enabled, 99)

        assertTrue(enabled.enabled)
        assertTrue(source.enabledValue)
        assertEquals(1, selected.selectedIndex)
        assertEquals(1, source.selected)
        assertFalse(initial.enabled)
    }

    private fun service(id: String) = ChatSearchService(id, id)

    private class FakeWebSearchSettingsDataSource : ChatWebSearchSettingsDataSource {
        var enabledValue = false
        var selected = 0
        var values: List<ChatSearchService> = emptyList()

        override fun enabled(): Boolean = enabledValue

        override fun setEnabled(enabled: Boolean) {
            enabledValue = enabled
        }

        override fun selectedIndex(): Int = selected

        override fun setSelectedIndex(index: Int) {
            selected = index
        }

        override fun services(): List<ChatSearchService> = values
    }
}
