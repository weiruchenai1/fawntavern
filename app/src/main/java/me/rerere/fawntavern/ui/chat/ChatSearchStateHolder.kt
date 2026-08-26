package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.fawntavern.data.search.SearchServiceOptions

/** Owns persisted web-search settings and exposes their UI projection. */
internal class ChatSearchStateHolder(
    private val controller: ChatWebSearchSettingsController,
) {
    private var settings by mutableStateOf(controller.load())

    val enabled: Boolean
        get() = settings.enabled

    val providerIndex: Int
        get() = settings.selectedIndex

    val services: List<SearchServiceOptions>
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
