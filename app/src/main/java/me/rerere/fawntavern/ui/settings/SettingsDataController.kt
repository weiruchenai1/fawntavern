package me.rerere.fawntavern.ui.settings

import android.content.Context
import android.graphics.Bitmap
import me.rerere.fawntavern.data.api.ApiConfigStore
import me.rerere.fawntavern.data.settings.DefaultModelStore
import me.rerere.fawntavern.data.settings.FontSizeStore
import me.rerere.fawntavern.data.settings.LanguageStore
import me.rerere.fawntavern.data.settings.Preferences
import me.rerere.fawntavern.data.settings.PreferencesStore
import me.rerere.fawntavern.data.settings.PromptLogStore
import me.rerere.fawntavern.data.settings.UserAvatarStore
import me.rerere.fawntavern.data.settings.UserProfileStore

internal data class FontPreviewData(
    val userName: String,
    val avatar: Bitmap?,
    val modelSpec: String,
)

internal interface SettingsDataSource {
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

internal class AndroidSettingsDataSource(
    private val context: Context,
) : SettingsDataSource {
    override fun language(): String = LanguageStore.getLanguage(context)
    override fun languageLabel(code: String): String = LanguageStore.getLabel(code)
    override fun setLanguage(code: String) = LanguageStore.setLanguage(context, code)
    override fun markLanguageChangePending() = LanguageStore.markPendingChange(context)
    override fun fontPreview(): FontPreviewData = FontPreviewData(
        userName = UserProfileStore.getName(context),
        avatar = UserAvatarStore.load(context),
        modelSpec = DefaultModelStore.get(context, DefaultModelStore.ROLE_CHAT).model
            .ifBlank { ApiConfigStore.loadConfig(context).currentModel },
    )
    override fun saveFontScale(scale: Float) = FontSizeStore.setScale(context, scale)
    override fun promptLogEnabled(): Boolean = PromptLogStore.isEnabled(context)
    override fun setPromptLogEnabled(enabled: Boolean) = PromptLogStore.setEnabled(context, enabled)
    override fun preferences(): Preferences = PreferencesStore.get(context)
    override fun savePreferences(preferences: Preferences) = PreferencesStore.set(context, preferences)
}

internal class SettingsDataController(
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
