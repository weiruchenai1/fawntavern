package me.rerere.fawntavern.domain.chat

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession

/** 聊天持久化契约；会话用例只依赖其中的 ChatSessionDataSource 能力。 */
interface ChatDataRepository : ChatSessionDataSource {
    data class SearchResult(val sessionId: String, val title: String, val content: String)

    fun messagesPaged(sessionId: String, initialKey: Int? = null): Flow<PagingData<ChatMessage>>
    suspend fun searchMessages(
        characterFile: String,
        query: String,
        limit: Int = 100,
    ): List<SearchResult>
    suspend fun messageCount(sessionId: String): Int
    suspend fun getMessage(sessionId: String, timestamp: Long): ChatMessage? =
        get(sessionId)?.messages?.firstOrNull { it.ts == timestamp }
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
    suspend fun saveLocalVariables(sessionId: String, variables: Map<String, String>)
    suspend fun collectUnusedAttachments()
}
