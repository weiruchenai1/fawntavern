package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import me.rerere.fawntavern.data.settings.ThemeMode
import me.rerere.fawntavern.domain.GenerationActionGuard
import me.rerere.fawntavern.ui.api.ApiConfigScreen
import me.rerere.fawntavern.ui.character.CharacterListScreen
import me.rerere.fawntavern.ui.extension.ExtensionsScreen
import me.rerere.fawntavern.ui.preset.PresetListScreen
import me.rerere.fawntavern.ui.regex.RegexListScreen
import me.rerere.fawntavern.ui.settings.AboutScreen
import me.rerere.fawntavern.ui.settings.DataManagementScreen
import me.rerere.fawntavern.ui.settings.DefaultModelPage
import me.rerere.fawntavern.ui.settings.FontSizeScreen
import me.rerere.fawntavern.ui.settings.LogsScreen
import me.rerere.fawntavern.ui.settings.PreferencesScreen
import me.rerere.fawntavern.ui.settings.PromptLogScreen
import me.rerere.fawntavern.ui.settings.CrashReportScreen
import me.rerere.fawntavern.ui.settings.SettingsScreen
import me.rerere.fawntavern.ui.settings.SystemLogScreen
import me.rerere.fawntavern.ui.settings.TtsConfigScreen
import me.rerere.fawntavern.ui.settings.WebSearchConfigScreen
import me.rerere.fawntavern.ui.statistics.StatisticsScreen
import me.rerere.fawntavern.ui.translator.TranslatorScreen
import me.rerere.fawntavern.ui.worldbook.WorldBookListScreen

/** 渲染覆盖聊天页的全屏页面。 */
@Composable
internal fun ChatDestinationHost(
    destination: ChatDestination,
    stateHolder: SaveableStateHolder,
    viewModel: ChatViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    solidBackground: Boolean,
    onSolidBackgroundChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onNavigate: (ChatDestination) -> Unit,
    onOpenSearchSession: (String) -> Unit,
) {
    stateHolder.SaveableStateProvider(destination.name) {
        when (destination) {
        ChatDestination.Translator -> {
            TranslatorScreen(
                onBack = onBack,
                apiConfig = viewModel.apiConfig,
                fallbackModelSpec = viewModel.displayModelSpec().orEmpty(),
            )
        }
        ChatDestination.Statistics -> { StatisticsScreen(onBack = onBack) }
        ChatDestination.Search -> {
            SearchScreen(
                charFile = viewModel.session?.charFile.orEmpty(),
                onBack = onBack,
                onOpenSession = onOpenSearchSession,
            )
        }
        ChatDestination.FontSize -> {
            FontSizeScreen(
                onBack = {
                    onBack()
                    viewModel.reloadUiSettings()
                },
                currentScale = viewModel.uiSettings.fontScale,
            )
        }
        ChatDestination.Preferences -> {
            PreferencesScreen(
                onBack = {
                    onBack()
                    viewModel.reloadUiSettings()
                },
                solidBackground = solidBackground,
                onSolidBackgroundChange = onSolidBackgroundChange,
            )
        }
        ChatDestination.Logs -> {
            LogsScreen(
                onBack = onBack,
                onOpenSystemLog = { onNavigate(ChatDestination.SystemLog) },
                onOpenPromptLog = { onNavigate(ChatDestination.PromptLog) },
            )
        }
        ChatDestination.SystemLog -> { SystemLogScreen(onBack = onBack) }
        ChatDestination.PromptLog -> { PromptLogScreen(onBack = onBack) }
        ChatDestination.CrashReport -> { CrashReportScreen(onBack = onBack) }
        ChatDestination.DataMgmt -> {
            DataManagementScreen(
                onBack = {
                    onBack()
                    viewModel.refreshAfterDataManagement()
                },
                destructiveActionsEnabled = GenerationActionGuard.allowsMutation(viewModel.generating),
            )
        }
        ChatDestination.ApiConfig -> {
            ApiConfigScreen(onBack = {
                onBack()
                viewModel.reloadApiConfig()
            })
        }
        ChatDestination.WorldBooks -> {
            WorldBookListScreen(onBack = {
                onBack()
                viewModel.reloadPromptData()
            })
        }
        ChatDestination.Characters -> {
            CharacterListScreen(onBack = {
                onBack()
                viewModel.refreshCurrentCard()
            })
        }
        ChatDestination.Presets -> {
            PresetListScreen(onBack = {
                onBack()
                viewModel.reloadPromptData()
            })
        }
        ChatDestination.Settings -> {
            SettingsScreen(
                onBack = onBack,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                onNavigateToPresets = { onNavigate(ChatDestination.Presets) },
                onNavigateToCharacters = { onNavigate(ChatDestination.Characters) },
                onNavigateToWorldBooks = { onNavigate(ChatDestination.WorldBooks) },
                onNavigateToApiConfig = { onNavigate(ChatDestination.ApiConfig) },
                onNavigateToDataManagement = { onNavigate(ChatDestination.DataMgmt) },
                onNavigateToFontSize = { onNavigate(ChatDestination.FontSize) },
                onNavigateToPreferences = { onNavigate(ChatDestination.Preferences) },
                onNavigateToLogs = { onNavigate(ChatDestination.Logs) },
                onNavigateToCrashReport = { onNavigate(ChatDestination.CrashReport) },
                onNavigateToExtensions = { onNavigate(ChatDestination.Extensions) },
                onNavigateToDefaultModel = { onNavigate(ChatDestination.DefaultModel) },
                onNavigateToWebSearch = { onNavigate(ChatDestination.WebSearch) },
                onNavigateToTts = { onNavigate(ChatDestination.Tts) },
                onNavigateToRegex = { onNavigate(ChatDestination.Regex) },
                onNavigateToAbout = { onNavigate(ChatDestination.About) },
            )
        }
        ChatDestination.Extensions -> {
            ExtensionsScreen(onBack = {
                onBack()
                viewModel.refreshExtensionSlots()
            })
        }
        ChatDestination.About -> { AboutScreen(onBack = onBack) }
        ChatDestination.DefaultModel -> { DefaultModelPage(onBack = onBack) }
        ChatDestination.WebSearch -> {
            WebSearchConfigScreen(onBack = {
                onBack()
                viewModel.reloadSearchConfig()
            })
        }
        ChatDestination.Tts -> { TtsConfigScreen(onBack = onBack) }
        ChatDestination.Regex -> {
            RegexListScreen(onBack = {
                onBack()
                viewModel.reloadPromptData()
            })
        }
        }
    }
}
