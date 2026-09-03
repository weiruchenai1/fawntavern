package me.rerere.fawntavern.data.chat

import android.content.Context
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import me.rerere.fawntavern.domain.chat.ChatDataRepository

/** Room-backed adapter for the domain chat persistence contract. */
class RoomChatDataRepository(
    context: Context,
) : ChatDataRepository {
    private val appContext = context.applicationContext

    override fun observeSessions(): Flow<List<ChatSession>> =
        ChatRepository.sessionsFlow(appContext)

    override fun messagesPaged(sessionId: String, initialKey: Int?): Flow<PagingData<ChatMessage>> =
        ChatRepository.messagesPaged(appContext, sessionId, initialKey)

    override suspend fun listSummaries() = ChatRepository.listSummaries(appContext)
    override suspend fun searchMessages(characterFile: String, query: String, limit: Int) =
        ChatRepository.searchMessages(appContext, characterFile, query, limit).map {
            ChatDataRepository.SearchResult(it.sessionId, it.title, it.content)
        }
    override suspend fun count() = ChatRepository.count(appContext)
    override suspend fun get(id: String) = ChatRepository.get(appContext, id)
    override suspend fun getMetadata(id: String) = ChatRepository.getMetadata(appContext, id)
    override suspend fun save(session: ChatSession) = ChatRepository.save(appContext, session)
    override suspend fun delete(id: String) = ChatRepository.delete(appContext, id)
    override suspend fun messageCount(sessionId: String) = ChatRepository.messageCount(appContext, sessionId)
    override suspend fun getMessage(sessionId: String, timestamp: Long) =
        ChatRepository.getMessage(appContext, sessionId, timestamp)
    override suspend fun putMessage(sessionId: String, message: ChatMessage) =
        ChatRepository.putMessage(appContext, sessionId, message)
    override suspend fun commitGeneration(sessionId: String, message: ChatMessage, timedWorldInfo: Map<String, Int>) =
        ChatRepository.commitGeneration(appContext, sessionId, message, timedWorldInfo)
    override suspend fun switchAlternative(sessionId: String, timestamp: Long, direction: Int) =
        ChatRepository.switchAlt(appContext, sessionId, timestamp, direction)
    override suspend fun deleteMessage(sessionId: String, timestamp: Long) =
        ChatRepository.deleteMessage(appContext, sessionId, timestamp)
    override suspend fun deleteAllVersions(sessionId: String, timestamp: Long) =
        ChatRepository.deleteAllVersions(appContext, sessionId, timestamp)
    override suspend fun editMessage(sessionId: String, timestamp: Long, content: String) =
        ChatRepository.editMessage(appContext, sessionId, timestamp, content)
    override suspend fun truncateAfter(sessionId: String, timestamp: Long) =
        ChatRepository.truncateAfter(appContext, sessionId, timestamp)
    override suspend fun updateTitle(sessionId: String, title: String) =
        ChatRepository.updateTitle(appContext, sessionId, title)
    override suspend fun updatePinned(sessionId: String, pinned: Boolean) =
        ChatRepository.updatePinned(appContext, sessionId, pinned)
    override suspend fun saveLocalVariables(sessionId: String, variables: Map<String, String>) =
        ChatRepository.saveLocalVariables(appContext, sessionId, variables)
    override suspend fun collectUnusedAttachments() = ChatRepository.collectUnusedAttachments(appContext)
}
