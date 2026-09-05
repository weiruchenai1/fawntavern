package me.rerere.fawntavern.di

import android.content.Context
import me.rerere.fawntavern.ui.api.AndroidApiConfigDataSource
import me.rerere.fawntavern.ui.api.ApiConfigController
import me.rerere.fawntavern.ui.character.AndroidCharacterEditorDataSource
import me.rerere.fawntavern.ui.character.AndroidCharacterLibraryDataSource
import me.rerere.fawntavern.ui.character.CharacterEditorController
import me.rerere.fawntavern.ui.character.CharacterLibraryController
import me.rerere.fawntavern.domain.chat.ChatDataRepository
import me.rerere.fawntavern.ui.chat.AndroidChatSearchDataSource
import me.rerere.fawntavern.ui.chat.AndroidChatUserProfileDataSource
import me.rerere.fawntavern.ui.chat.ChatSearchController
import me.rerere.fawntavern.ui.chat.ChatUserProfileController
import me.rerere.fawntavern.ui.extension.AndroidExtensionSettingsDataSource
import me.rerere.fawntavern.ui.extension.ExtensionSettingsController
import me.rerere.fawntavern.ui.preset.AndroidPresetDataSource
import me.rerere.fawntavern.ui.preset.PresetDataController
import me.rerere.fawntavern.ui.regex.AndroidRegexLibraryDataSource
import me.rerere.fawntavern.ui.regex.RegexLibraryController
import me.rerere.fawntavern.ui.settings.AndroidDataManagementDataSource
import me.rerere.fawntavern.ui.settings.AndroidDefaultModelDataSource
import me.rerere.fawntavern.ui.settings.AndroidDiagnosticsDataSource
import me.rerere.fawntavern.ui.settings.AndroidSettingsDataSource
import me.rerere.fawntavern.ui.settings.AndroidTtsConfigDataSource
import me.rerere.fawntavern.ui.settings.AndroidWebSearchConfigDataSource
import me.rerere.fawntavern.ui.settings.DataManagementController
import me.rerere.fawntavern.ui.settings.DefaultModelController
import me.rerere.fawntavern.ui.settings.DiagnosticsController
import me.rerere.fawntavern.ui.settings.SettingsDataController
import me.rerere.fawntavern.ui.settings.TtsConfigController
import me.rerere.fawntavern.ui.settings.WebSearchConfigController
import me.rerere.fawntavern.ui.statistics.AndroidStatisticsDataSource
import me.rerere.fawntavern.ui.statistics.StatisticsController
import me.rerere.fawntavern.ui.translator.AndroidTranslatorDataSource
import me.rerere.fawntavern.ui.translator.TranslatorController
import me.rerere.fawntavern.ui.worldbook.AndroidWorldBookDataSource
import me.rerere.fawntavern.ui.worldbook.AndroidWorldInfoSettingsDataSource
import me.rerere.fawntavern.ui.worldbook.WorldBookDataController
import me.rerere.fawntavern.ui.worldbook.WorldInfoSettingsController

/** 跨页面复用的类型化 Feature 入口；具体 Store/Repository 只在 Android 适配器中出现。 */
internal class AppFeatureControllers(context: Context, chatRepository: ChatDataRepository) {
    private val appContext = context.applicationContext

    val apiConfig = ApiConfigController(AndroidApiConfigDataSource(appContext))
    val characterLibrary = CharacterLibraryController(AndroidCharacterLibraryDataSource(appContext))
    val characterEditor = CharacterEditorController(AndroidCharacterEditorDataSource(appContext))
    val chatSearch = ChatSearchController(AndroidChatSearchDataSource(appContext, chatRepository))
    val chatUserProfile = ChatUserProfileController(AndroidChatUserProfileDataSource(appContext))
    val extensions = ExtensionSettingsController(AndroidExtensionSettingsDataSource(appContext))
    val presets = PresetDataController(AndroidPresetDataSource(appContext))
    val regex = RegexLibraryController(AndroidRegexLibraryDataSource(appContext))
    val settings = SettingsDataController(AndroidSettingsDataSource(appContext))
    val dataManagement = DataManagementController(AndroidDataManagementDataSource(appContext))
    val defaultModels = DefaultModelController(AndroidDefaultModelDataSource(appContext))
    val diagnostics = DiagnosticsController(AndroidDiagnosticsDataSource(appContext))
    val statistics = StatisticsController(AndroidStatisticsDataSource(appContext))
    val translator = TranslatorController(AndroidTranslatorDataSource(appContext))
    val tts = TtsConfigController(AndroidTtsConfigDataSource(appContext))
    val webSearch = WebSearchConfigController(AndroidWebSearchConfigDataSource(appContext))
    val worldBooks = WorldBookDataController(AndroidWorldBookDataSource(appContext))
    val worldInfoSettings = WorldInfoSettingsController(AndroidWorldInfoSettingsDataSource(appContext))
}
