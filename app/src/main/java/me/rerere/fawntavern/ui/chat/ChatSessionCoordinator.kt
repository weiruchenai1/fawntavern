package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.flow.Flow
import me.rerere.fawntavern.data.chat.ChatDataRepository
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.domain.ConversationOps

internal interface ChatSessionDataSource {
    fun observeSessions(): Flow<List<ChatSession>>
    suspend fun listSummaries(): List<ChatSession>
    suspend fun count(): Int
    suspend fun get(id: String): ChatSession?
    suspend fun save(session: ChatSession)
    suspend fun delete(id: String)
    suspend fun updateTitle(id: String, title: String)
    suspend fun updatePinned(id: String, pinned: Boolean)
    suspend fun truncateAfter(id: String, timestamp: Long)
}

internal class RepositoryChatSessionDataSource(
    private val repository: ChatDataRepository,
) : ChatSessionDataSource {
    override fun observeSessions(): Flow<List<ChatSession>> = repository.observeSessions()
    override suspend fun listSummaries(): List<ChatSession> = repository.listSummaries()
    override suspend fun count(): Int = repository.count()
    override suspend fun get(id: String): ChatSession? = repository.get(id)
    override suspend fun save(session: ChatSession) = repository.save(session)
    override suspend fun delete(id: String) = repository.delete(id)
    override suspend fun updateTitle(id: String, title: String) =
        repository.updateTitle(id, title)
    override suspend fun updatePinned(id: String, pinned: Boolean) =
        repository.updatePinned(id, pinned)
    override suspend fun truncateAfter(id: String, timestamp: Long) =
        repository.truncateAfter(id, timestamp)
}

internal data class DeletedSessionChoice(
    val nextSessionId: String?,
    val shouldCreateNew: Boolean,
)

internal fun chooseSessionAfterDelete(
    deletedId: String,
    currentSession: ChatSession?,
    summaries: List<ChatSession>,
    newChatOnDeleteTopic: Boolean,
): DeletedSessionChoice {
    if (currentSession?.id != deletedId) return DeletedSessionChoice(null, false)
    if (newChatOnDeleteTopic) return DeletedSessionChoice(null, true)
    return DeletedSessionChoice(
        nextSessionId = summaries.firstOrNull { it.charFile == currentSession.charFile }?.id,
        shouldCreateNew = true,
    )
}

internal class ChatSessionCoordinator(
    private val dataSource: ChatSessionDataSource,
) {
    fun observeSessions(): Flow<List<ChatSession>> = dataSource.observeSessions()

    suspend fun listSummaries(): List<ChatSession> = dataSource.listSummaries()

    suspend fun count(): Int = dataSource.count()

    suspend fun open(id: String): ChatSession? = dataSource.get(id)

    suspend fun create(
        card: CharacterCard?,
        charFile: String,
        charName: String,
        persist: Boolean,
    ): ChatSession {
        val session = ConversationOps.newSession(card, charFile, charName)
        if (persist) dataSource.save(session)
        return session
    }

    suspend fun delete(
        id: String,
        currentSession: ChatSession?,
        currentCard: CharacterCard?,
        newChatOnDeleteTopic: Boolean,
    ): ChatSession? {
        dataSource.delete(id)
        val summaries = dataSource.listSummaries()
        val choice = chooseSessionAfterDelete(id, currentSession, summaries, newChatOnDeleteTopic)
        if (choice.nextSessionId != null) return dataSource.get(choice.nextSessionId)
        if (choice.shouldCreateNew && currentSession != null) {
            return ConversationOps.newSession(currentCard, currentSession.charFile, currentSession.charName)
        }
        return null
    }

    suspend fun rename(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isNotBlank()) dataSource.updateTitle(id, trimmed)
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        dataSource.updatePinned(id, pinned)
    }

    suspend fun truncateAfter(id: String, timestamp: Long) {
        dataSource.truncateAfter(id, timestamp)
    }
}
