package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class ChatSearchState(
    val enabled: Boolean,
    val providerIndex: Int,
    val providerName: String,
    val services: List<ChatSearchService>,
    val builtInAvailable: Boolean,
    val builtInEnabled: Boolean,
)

/** 持有聊天功能使用的联网搜索设置快照。 */
class ChatSearchStateHolder(
    private val controller: ChatWebSearchSettingsController,
) {
    private var settings by mutableStateOf(controller.load())

    val enabled: Boolean
        get() = settings.enabled

    val providerIndex: Int
        get() = settings.selectedIndex

    val services: List<ChatSearchService>
        get() = settings.services

    val providerName: String
        get() = settings.providerName

    fun toggle() {
        settings = controller.toggle(settings)
    }

    fun selectProvider(index: Int) {
        settings = controller.select(settings, index)
    }

    fun reload() {
        settings = controller.load()
    }
}
