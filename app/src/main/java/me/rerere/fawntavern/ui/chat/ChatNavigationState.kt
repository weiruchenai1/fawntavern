package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList

/** Full-screen destinations layered above the chat screen. */
internal enum class ChatDestination {
    Settings,
    Presets,
    Characters,
    WorldBooks,
    ApiConfig,
    DataMgmt,
    FontSize,
    Preferences,
    PromptLog,
    Search,
    Extensions,
    About,
    DefaultModel,
    WebSearch,
    Tts,
}

private val ChatNavigationSaver = listSaver<SnapshotStateList<ChatDestination>, String>(
    save = { stack -> stack.map { it.name } },
    restore = { names ->
        names.mapNotNullTo(mutableStateListOf<ChatDestination>()) { name ->
            ChatDestination.entries.firstOrNull { it.name == name }
        }
    },
)

@Composable
internal fun rememberChatNavigationStack(
    startAtSettings: Boolean,
): SnapshotStateList<ChatDestination> {
    return rememberSaveable(saver = ChatNavigationSaver) {
        mutableStateListOf<ChatDestination>().apply {
            if (startAtSettings) add(ChatDestination.Settings)
        }
    }
}
