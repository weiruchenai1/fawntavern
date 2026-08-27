package me.rerere.fawntavern.ui.chat

data class ChatSearchService(
    val id: String,
    val displayName: String,
)

data class ChatWebSearchSettings(
    val enabled: Boolean,
    val selectedIndex: Int,
    val services: List<ChatSearchService>,
) {
    val providerName: String
        get() = services.getOrNull(selectedIndex)?.displayName.orEmpty()
}

interface ChatWebSearchSettingsDataSource {
    fun enabled(): Boolean
    fun setEnabled(enabled: Boolean)
    fun selectedIndex(): Int
    fun setSelectedIndex(index: Int)
    fun services(): List<ChatSearchService>
}

class ChatWebSearchSettingsController(
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

    private fun clampIndex(index: Int, services: List<ChatSearchService>): Int =
        index.coerceIn(0, services.lastIndex.coerceAtLeast(0))
}
