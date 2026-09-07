package me.rerere.fawntavern.ui.chat

import androidx.paging.PagingData
import kotlinx.coroutines.flow.emptyFlow
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.chat.MessageAlternatives
import me.rerere.fawntavern.domain.chat.ChatDataRepository

internal open class InMemoryChatRepository(vararg initial: ChatSession) : ChatDataRepository {
    val sessions = initial.associateBy { it.id }.toMutableMap()
    var beforeEdit: suspend (String) -> Unit = {}

    override fun observeSessions() = emptyFlow<List<ChatSession>>()
    override fun messagesPaged(sessionId: String, initialKey: Int?) = emptyFlow<PagingData<ChatMessage>>()
    override suspend fun listSummaries() = sessions.values.toList()
    override suspend fun searchMessages(characterFile: String, query: String, limit: Int) =
        emptyList<ChatDataRepository.SearchResult>()
    override suspend fun count() = sessions.size
    override suspend fun get(id: String) = sessions[id]
    override suspend fun save(session: ChatSession) { sessions[session.id] = session }
    override suspend fun delete(id: String) { sessions.remove(id) }
    override suspend fun messageCount(sessionId: String) = sessions[sessionId]?.messages?.size ?: 0
    override suspend fun putMessage(sessionId: String, message: ChatMessage) {
        val session = sessions.getValue(sessionId)
        sessions[sessionId] = session.copy(messages = (session.messages.filterNot { it.ts == message.ts } + message).sortedBy { it.ts })
    }
    override suspend fun commitGeneration(sessionId: String, message: ChatMessage, timedWorldInfo: Map<String, Int>) {
        putMessage(sessionId, message)
        sessions[sessionId] = sessions.getValue(sessionId).copy(timedWi = timedWorldInfo)
    }
    override suspend fun switchAlternative(sessionId: String, timestamp: Long, direction: Int): Boolean {
        val message = getMessage(sessionId, timestamp) ?: return false
        val changed = MessageAlternatives.switch(message, direction) ?: return false
        putMessage(sessionId, changed)
        return true
    }
    override suspend fun deleteMessage(sessionId: String, timestamp: Long) {
        val message = getMessage(sessionId, timestamp) ?: return
        val changed = MessageAlternatives.deleteCurrent(message)
        if (changed == null) deleteAllVersions(sessionId, timestamp) else putMessage(sessionId, changed)
    }
    override suspend fun deleteAllVersions(sessionId: String, timestamp: Long) {
        val session = sessions.getValue(sessionId)
        sessions[sessionId] = session.copy(messages = session.messages.filterNot { it.ts == timestamp })
    }
    override suspend fun editMessage(sessionId: String, timestamp: Long, content: String) {
        beforeEdit(content)
        putMessage(sessionId, requireNotNull(getMessage(sessionId, timestamp)).copy(content = content))
    }
    override suspend fun truncateAfter(id: String, timestamp: Long) {
        val session = sessions.getValue(id)
        sessions[id] = session.copy(messages = session.messages.filter { it.ts <= timestamp })
    }
    override suspend fun updateTitle(id: String, title: String) { sessions[id] = sessions.getValue(id).copy(title = title) }
    override suspend fun updatePinned(id: String, pinned: Boolean) { sessions[id] = sessions.getValue(id).copy(pinned = pinned) }
    override suspend fun saveLocalVariables(sessionId: String, variables: Map<String, String>) {
        sessions[sessionId] = sessions.getValue(sessionId).copy(localVariables = variables)
    }
    override suspend fun collectUnusedAttachments() = Unit
}

internal object EmptyPromptContextDataSource : ChatPromptContextDataSource {
    override suspend fun load(charFile: String) = ChatPromptContextSnapshot(
        ChatLoadedPromptContext(charFile, null, emptyList(), null), null,
    )
    override suspend fun loadCard(charFile: String) = null
    override suspend fun loadGlobalRegex() = emptyList<me.rerere.fawntavern.data.character.CharRegex>()
    override suspend fun ensureDefaultCharacter(defaultPresetName: String, defaultCharacterName: String) = ""
}
