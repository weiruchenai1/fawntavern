package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 持有聊天界面使用的持久化设置快照。 */
class ChatUiSettingsStateHolder(
    private val controller: ChatUiSettingsController,
) {
    var value by mutableStateOf(controller.load())
        private set

    fun reload() {
        value = controller.load()
    }
}
