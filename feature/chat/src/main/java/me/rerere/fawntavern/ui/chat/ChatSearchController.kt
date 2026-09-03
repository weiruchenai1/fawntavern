package me.rerere.fawntavern.ui.chat

data class ChatSearchHit(
    val sessionId: String,
    val title: String,
    val snippet: String,
)

interface ChatSearchDataSource {
    fun history(): List<String>
    fun addHistory(query: String)
    fun removeHistory(query: String)
    fun clearHistory()
    suspend fun search(charFile: String, query: String): List<ChatSearchHit>
}

class ChatSearchController(
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
