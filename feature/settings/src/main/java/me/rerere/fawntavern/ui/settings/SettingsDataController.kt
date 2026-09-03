package me.rerere.fawntavern.ui.settings

import android.graphics.Bitmap
import me.rerere.fawntavern.data.settings.Preferences

data class FontPreviewData(
    val userName: String,
    val avatar: Bitmap?,
    val modelSpec: String,
)

interface SettingsDataSource {
    fun language(): String
    fun languageLabel(code: String): String
    fun setLanguage(code: String)
    fun markLanguageChangePending()
    fun fontPreview(): FontPreviewData
    fun saveFontScale(scale: Float)
    fun promptLogEnabled(): Boolean
    fun setPromptLogEnabled(enabled: Boolean)
    fun preferences(): Preferences
    fun savePreferences(preferences: Preferences)
}

class SettingsDataController(
    private val dataSource: SettingsDataSource,
) {
    fun language(): String = dataSource.language()
    fun languageLabel(code: String): String = dataSource.languageLabel(code)
    fun selectLanguage(code: String): String {
        dataSource.setLanguage(code)
        dataSource.markLanguageChangePending()
        return code
    }
    fun fontPreview(): FontPreviewData = dataSource.fontPreview()
    fun saveFontScale(scale: Float): Float {
        dataSource.saveFontScale(scale)
        return scale
    }
    fun promptLogEnabled(): Boolean = dataSource.promptLogEnabled()
    fun setPromptLogEnabled(enabled: Boolean): Boolean {
        dataSource.setPromptLogEnabled(enabled)
        return enabled
    }
    fun switchHapticEnabled(): Boolean = dataSource.preferences().switchHaptic
    fun preferences(): Preferences = dataSource.preferences()
    fun savePreferences(preferences: Preferences): Preferences {
        dataSource.savePreferences(preferences)
        return preferences
    }
}
