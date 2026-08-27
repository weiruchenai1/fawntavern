package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.domain.chat.ChatDataRepository
import me.rerere.fawntavern.data.settings.SearchHistoryStore
import me.rerere.fawntavern.domain.chat.buildSearchSnippet

internal data class ChatSearchHit(
    val sessionId: String,
    val title: String,
    val snippet: String,
)

internal interface ChatSearchDataSource {
    fun history(): List<String>
    fun addHistory(query: String)
    fun removeHistory(query: String)
    fun clearHistory()
    suspend fun search(charFile: String, query: String): List<ChatSearchHit>
}

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

internal class ChatSearchController(
    private val dataSource: ChatSearchDataSource,
) {
    fun history(): List<String> = dataSource.history()

    suspend fun search(charFile: String, query: String): List<ChatSearchHit> {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) emptyList() else dataSource.search(charFile, trimmed)
    }

    fun record(query: String) = dataSource.addHistory(query.trim())

    fun removeHistory(query: String): List<String> {
        dataSource.removeHistory(query)
        return dataSource.history()
    }

    fun clearHistory(): List<String> {
        dataSource.clearHistory()
        return emptyList()
    }
}
