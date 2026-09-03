package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.data.settings.SearchHistoryStore
import me.rerere.fawntavern.domain.chat.ChatDataRepository
import me.rerere.fawntavern.domain.chat.buildSearchSnippet

internal class AndroidChatSearchDataSource(
    private val context: Context,
    private val repository: ChatDataRepository,
) : ChatSearchDataSource {
    override fun history(): List<String> = SearchHistoryStore.getHistory(context)

    override fun addHistory(query: String) = SearchHistoryStore.add(context, query)

    override fun removeHistory(query: String) = SearchHistoryStore.remove(context, query)

    override fun clearHistory() = SearchHistoryStore.clear(context)

    override suspend fun search(charFile: String, query: String): List<ChatSearchHit> =
        repository.searchMessages(charFile, query).map { result ->
            ChatSearchHit(
                sessionId = result.sessionId,
                title = result.title,
                snippet = buildSearchSnippet(result.content, query),
            )
        }
}
