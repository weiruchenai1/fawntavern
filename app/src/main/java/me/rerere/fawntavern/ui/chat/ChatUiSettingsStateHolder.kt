package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Owns the persisted UI settings snapshot consumed by the chat feature. */
internal class ChatUiSettingsStateHolder(
    private val controller: ChatUiSettingsController,
) {
    var value by mutableStateOf(controller.load())
        private set

    fun reload() {
        value = controller.load()
    }
}
