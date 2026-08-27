package me.rerere.fawntavern.ui.chat

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import me.rerere.fawntavern.domain.chat.ChatDataRepository
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Test

class CommitChatGenerationUseCaseTest {
    @Test
    fun commitsMessageAndTimedWorldInfoBeforeReadingSession() = runBlocking {
        val session = ChatSession(id = "session")
        val repository = RecordingChatRepository(session)
        val message = ChatMessage(role = "assistant", content = "done", ts = 2L)

        val result = CommitChatGenerationUseCase(repository)(
            sessionId = session.id,
            message = message,
            timedWorldInfo = mapOf("entry" to 3),
        )

        assertEquals(listOf("commit", "get"), repository.operations)
        assertEquals(message, repository.committedMessage)
        assertEquals(mapOf("entry" to 3), repository.committedTimedWorldInfo)
        assertEquals(session, result)
    }
}

private class RecordingChatRepository(
    private val session: ChatSession,
) : ChatDataRepository {
    val operations = mutableListOf<String>()
    var committedMessage: ChatMessage? = null
    var committedTimedWorldInfo: Map<String, Int>? = null

    override fun observeSessions(): Flow<List<ChatSession>> = emptyFlow()
    override fun messagesPaged(sessionId: String, initialKey: Int?): Flow<PagingData<ChatMessage>> =
        emptyFlow()
    override suspend fun listSummaries(): List<ChatSession> = emptyList()
    override suspend fun searchMessages(
        characterFile: String,
        query: String,
        limit: Int,
    ): List<ChatDataRepository.SearchResult> = emptyList()
    override suspend fun count(): Int = 0
    override suspend fun get(id: String): ChatSession? {
        operations += "get"
        return session
    }
    override suspend fun save(session: ChatSession) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun messageCount(sessionId: String): Int = 0
    override suspend fun putMessage(sessionId: String, message: ChatMessage) = Unit
    override suspend fun commitGeneration(
        sessionId: String,
        message: ChatMessage,
        timedWorldInfo: Map<String, Int>,
    ) {
        operations += "commit"
        committedMessage = message
        committedTimedWorldInfo = timedWorldInfo
    }
    override suspend fun switchAlternative(
        sessionId: String,
        timestamp: Long,
        direction: Int,
    ): Boolean = false
    override suspend fun deleteMessage(sessionId: String, timestamp: Long) = Unit
    override suspend fun deleteAllVersions(sessionId: String, timestamp: Long) = Unit
    override suspend fun editMessage(sessionId: String, timestamp: Long, content: String) = Unit
    override suspend fun truncateAfter(sessionId: String, timestamp: Long) = Unit
    override suspend fun updateTitle(sessionId: String, title: String) = Unit
    override suspend fun updatePinned(sessionId: String, pinned: Boolean) = Unit
    override suspend fun saveLocalVariables(sessionId: String, variables: Map<String, String>) = Unit
    override suspend fun collectUnusedAttachments() = Unit
}
