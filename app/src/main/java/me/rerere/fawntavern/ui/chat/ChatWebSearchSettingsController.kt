package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.data.search.SearchServiceOptions
import me.rerere.fawntavern.data.settings.SearchStore

internal data class ChatWebSearchSettings(
    val enabled: Boolean,
    val selectedIndex: Int,
    val services: List<SearchServiceOptions>,
) {
    val providerName: String
        get() = services.getOrNull(selectedIndex)?.displayName.orEmpty()
}

internal interface ChatWebSearchSettingsDataSource {
    fun enabled(): Boolean
    fun setEnabled(enabled: Boolean)
    fun selectedIndex(): Int
    fun setSelectedIndex(index: Int)
    fun services(): List<SearchServiceOptions>
}

internal class AndroidChatWebSearchSettingsDataSource(
    private val context: Context,
) : ChatWebSearchSettingsDataSource {
    override fun enabled(): Boolean = SearchStore.isEnabled(context)

    override fun setEnabled(enabled: Boolean) = SearchStore.setEnabled(context, enabled)

    override fun selectedIndex(): Int = SearchStore.getSelectedIndex(context)

    override fun setSelectedIndex(index: Int) = SearchStore.setSelectedIndex(context, index)

    override fun services(): List<SearchServiceOptions> = SearchStore.getServices(context)
}

internal class ChatWebSearchSettingsController(
    private val dataSource: ChatWebSearchSettingsDataSource,
) {
    fun load(): ChatWebSearchSettings {
        val services = dataSource.services()
        return ChatWebSearchSettings(
            enabled = dataSource.enabled(),
            selectedIndex = clampIndex(dataSource.selectedIndex(), services),
            services = services,
        )
    }

    fun toggle(current: ChatWebSearchSettings): ChatWebSearchSettings {
        val enabled = !current.enabled
        dataSource.setEnabled(enabled)
        return current.copy(enabled = enabled)
    }

    fun select(current: ChatWebSearchSettings, index: Int): ChatWebSearchSettings {
        val selected = clampIndex(index, current.services)
        dataSource.setSelectedIndex(selected)
        return current.copy(selectedIndex = selected)
    }

    private fun clampIndex(index: Int, services: List<SearchServiceOptions>): Int =
        index.coerceIn(0, services.lastIndex.coerceAtLeast(0))
}
