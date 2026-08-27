package me.rerere.fawntavern.domain.chat

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession

/** Chat persistence contract consumed by chat use cases and implemented by the data layer. */
interface ChatDataRepository {
    data class SearchResult(val sessionId: String, val title: String, val content: String)

    fun observeSessions(): Flow<List<ChatSession>>
    fun messagesPaged(sessionId: String, initialKey: Int? = null): Flow<PagingData<ChatMessage>>
    suspend fun listSummaries(): List<ChatSession>
    suspend fun searchMessages(
        characterFile: String,
        query: String,
        limit: Int = 100,
    ): List<SearchResult>
    suspend fun count(): Int
    suspend fun get(id: String): ChatSession?
    suspend fun save(session: ChatSession)
    suspend fun delete(id: String)
    suspend fun messageCount(sessionId: String): Int
    suspend fun putMessage(sessionId: String, message: ChatMessage)
    suspend fun commitGeneration(
        sessionId: String,
        message: ChatMessage,
        timedWorldInfo: Map<String, Int>,
    )
    suspend fun switchAlternative(sessionId: String, timestamp: Long, direction: Int): Boolean
    suspend fun deleteMessage(sessionId: String, timestamp: Long)
    suspend fun deleteAllVersions(sessionId: String, timestamp: Long)
    suspend fun editMessage(sessionId: String, timestamp: Long, content: String)
    suspend fun truncateAfter(sessionId: String, timestamp: Long)
    suspend fun updateTitle(sessionId: String, title: String)
    suspend fun updatePinned(sessionId: String, pinned: Boolean)
    suspend fun saveLocalVariables(sessionId: String, variables: Map<String, String>)
    suspend fun collectUnusedAttachments()
}
