package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import me.rerere.fawntavern.FawnTavernApplication
import me.rerere.fawntavern.data.settings.ThemeMode

/** Route-level entry point. ChatContent owns rendering; ChatViewModel owns screen state. */
@Composable
fun ChatScreen(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    solidBackground: Boolean = false,
    onSolidBackgroundChange: (Boolean) -> Unit = {},
    startAtSettings: Boolean = false,
) {
    val application = LocalContext.current.applicationContext as FawnTavernApplication
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(application, application.container),
    )
    ChatContent(
        vm = viewModel,
        themeMode = themeMode,
        onThemeModeChange = onThemeModeChange,
        solidBackground = solidBackground,
        onSolidBackgroundChange = onSolidBackgroundChange,
        startAtSettings = startAtSettings,
    )
}
