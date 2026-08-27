package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.data.settings.SearchStore

internal class AndroidChatWebSearchSettingsDataSource(
    private val context: Context,
) : ChatWebSearchSettingsDataSource {
    override fun enabled(): Boolean = SearchStore.isEnabled(context)

    override fun setEnabled(enabled: Boolean) = SearchStore.setEnabled(context, enabled)

    override fun selectedIndex(): Int = SearchStore.getSelectedIndex(context)

    override fun setSelectedIndex(index: Int) = SearchStore.setSelectedIndex(context, index)

    override fun services(): List<ChatSearchService> = SearchStore.getServices(context).map {
        ChatSearchService(id = it.id, displayName = it.displayName)
    }
}
