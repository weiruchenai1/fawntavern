package me.rerere.fawntavern.ui.settings

import me.rerere.fawntavern.data.settings.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDataControllerTest {
    @Test
    fun writesLanguagePreferencesAndScalarSettingsThroughDataSource() {
        val source = FakeSettingsDataSource()
        val controller = SettingsDataController(source)
        val preferences = Preferences(longPressHaptic = false)

        assertEquals("en", controller.selectLanguage("en"))
        assertEquals(1.25f, controller.saveFontScale(1.25f), 0f)
        assertTrue(controller.setPromptLogEnabled(true))
        assertEquals(preferences, controller.savePreferences(preferences))

        assertEquals("en", source.currentLanguage)
        assertTrue(source.languageChangePending)
        assertEquals(1.25f, source.savedFontScale, 0f)
        assertTrue(source.promptLogValue)
        assertFalse(source.storedPreferences.longPressHaptic)
    }

    private class FakeSettingsDataSource : SettingsDataSource {
        var currentLanguage = "system"
        var languageChangePending = false
        var savedFontScale = 1f
        var promptLogValue = false
        var storedPreferences = Preferences()

        override fun language(): String = currentLanguage
        override fun languageLabel(code: String): String = code
        override fun setLanguage(code: String) { currentLanguage = code }
        override fun markLanguageChangePending() { languageChangePending = true }
        override fun fontPreview(): FontPreviewData = FontPreviewData("user", null, "")
        override fun saveFontScale(scale: Float) { savedFontScale = scale }
        override fun promptLogEnabled(): Boolean = promptLogValue
        override fun setPromptLogEnabled(enabled: Boolean) { promptLogValue = enabled }
        override fun preferences(): Preferences = storedPreferences
        override fun savePreferences(preferences: Preferences) { storedPreferences = preferences }
    }
}
