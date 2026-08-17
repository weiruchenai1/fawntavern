package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.settings.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiSettingsControllerTest {
    @Test
    fun loadReturnsLatestPreferencesAndFontScale() {
        val source = FakeChatUiSettingsDataSource()
        val controller = ChatUiSettingsController(source)

        val initial = controller.load()
        source.preferences = Preferences(
            showChatBarCharacterName = false,
            showChatBarModelName = false,
            showChatBarProvider = false,
            showUserAvatar = false,
            characterMarkdown = false,
            newChatOnLaunch = false,
            enterToSend = true,
        )
        source.scale = 1.25f
        val refreshed = controller.load()

        assertTrue(initial.showChatBarCharacterName)
        assertTrue(initial.showChatBarModelName)
        assertTrue(initial.showChatBarProvider)
        assertTrue(initial.showUserAvatar)
        assertEquals(1.0f, initial.fontScale, 0.0f)
        assertFalse(refreshed.showChatBarCharacterName)
        assertFalse(refreshed.showChatBarModelName)
        assertFalse(refreshed.showChatBarProvider)
        assertFalse(refreshed.showUserAvatar)
        assertFalse(refreshed.characterMarkdown)
        assertFalse(refreshed.newChatOnLaunch)
        assertTrue(refreshed.enterToSend)
        assertEquals(1.25f, refreshed.fontScale, 0.0f)
    }

    private class FakeChatUiSettingsDataSource : ChatUiSettingsDataSource {
        var preferences = Preferences()
        var scale = 1.0f

        override fun preferences(): Preferences = preferences

        override fun fontScale(): Float = scale
    }
}
