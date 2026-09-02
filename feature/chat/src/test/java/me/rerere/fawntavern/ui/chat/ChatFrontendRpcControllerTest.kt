package me.rerere.fawntavern.ui.chat

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.chat.MsgAlt
import me.rerere.fawntavern.domain.chat.ChatDataRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFrontendRpcControllerTest {
    @Test
    fun messageDataIsPersistedWithItsSelectedSwipe() = runBlocking {
        val repository = FakeRepository(
            ChatSession(
                id = "chat",
                messages = listOf(
                    ChatMessage(
                        role = "assistant",
                        content = "first",
                        dataJson = "{\"value\":1}",
                        alts = listOf(
                            MsgAlt(content = "first", dataJson = "{\"value\":1}"),
                            MsgAlt(content = "second", dataJson = "{\"value\":2}"),
                        ),
                    ),
                ),
            ),
        )
        var current = requireNotNull(repository.get("chat"))
        val controller = controller(repository, { current }, { current = it })

        controller.call(
            "chat.set-messages",
            JSONObject().put("messages", JSONArray().put(
                JSONObject().put("message_id", 0).put("swipe_id", 1).put("data", JSONObject().put("value", 3)),
            )).toString(),
        )

        val message = requireNotNull(repository.get("chat")).messages.single()
        assertEquals(1, message.altIdx)
        assertEquals("second", message.content)
        assertEquals("{\"value\":3}", message.dataJson)
        assertEquals("{\"value\":3}", message.alts[1].dataJson)
        assertEquals("{\"value\":1}", message.alts[0].dataJson)
    }

    @Test
    fun createAndDeleteUseFrontendMessageIndexes() = runBlocking {
        val repository = FakeRepository(
            ChatSession(
                id = "chat",
                messages = listOf(
                    ChatMessage(role = "user", content = "one", ts = 10),
                    ChatMessage(role = "assistant", content = "three", ts = 30),
                ),
            ),
        )
        var current = requireNotNull(repository.get("chat"))
        val controller = controller(repository, { current }, { current = it })

        controller.call(
            "chat.create-messages",
            JSONObject().put("insert_before", 1).put("messages", JSONArray().put(
                JSONObject().put("role", "assistant").put("message", "two"),
            )).toString(),
        )
        assertEquals(listOf("one", "two", "three"), requireNotNull(repository.get("chat")).messages.map { it.content })

        controller.call("chat.delete-messages", JSONObject().put("message_ids", JSONArray().put(1)).toString())
        assertEquals(listOf("one", "three"), requireNotNull(repository.get("chat")).messages.map { it.content })
    }

    @Test
    fun getMessagesReturnsABoundedPageWithSessionMessageIds() = runBlocking {
        val repository = FakeRepository(
            ChatSession(
                id = "chat",
                messages = listOf(
                    ChatMessage(role = "user", content = "zero", ts = 10),
                    ChatMessage(role = "assistant", content = "one", ts = 20),
                    ChatMessage(role = "user", content = "two", ts = 30),
                ),
            ),
        )
        var current = requireNotNull(repository.get("chat"))
        val controller = controller(repository, { current }, { current = it })

        val result = JSONObject(controller.call("chat.get-messages", "{\"offset\":1,\"limit\":2}"))
        val messages = result.getJSONArray("messages")

        assertEquals(3, result.getInt("total"))
        assertEquals(1, result.getInt("offset"))
        assertEquals(1, messages.getJSONObject(0).getInt("message_id"))
        assertEquals("one", messages.getJSONObject(0).getString("message"))
        assertEquals(2, messages.getJSONObject(1).getInt("message_id"))
    }

    private fun controller(
        repository: FakeRepository,
        current: () -> ChatSession,
        replace: (ChatSession) -> Unit,
    ) = ChatFrontendRpcController(
        repository = repository,
        currentSession = current,
        replaceCurrent = replace,
        loadGlobalVariables = { emptyMap() },
        saveGlobalVariables = {},
        scopedVariables = EmptyChatFrontendVariableDataSource,
        scopeOwner = { _, _ -> "owner" },
        emitEvent = { _, _ -> },
    )

    private class FakeRepository(initial: ChatSession) : ChatDataRepository {
        private var session = initial
        override fun observeSessions(): Flow<List<ChatSession>> = emptyFlow()
        override fun messagesPaged(sessionId: String, initialKey: Int?): Flow<PagingData<ChatMessage>> = emptyFlow()
        override suspend fun listSummaries(): List<ChatSession> = listOf(session)
        override suspend fun searchMessages(characterFile: String, query: String, limit: Int) = emptyList<ChatDataRepository.SearchResult>()
        override suspend fun count(): Int = 1
        override suspend fun get(id: String): ChatSession? = session.takeIf { it.id == id }
        override suspend fun save(session: ChatSession) { this.session = session }
        override suspend fun delete(id: String) = Unit
        override suspend fun messageCount(sessionId: String): Int = session.messages.size
        override suspend fun putMessage(sessionId: String, message: ChatMessage) = Unit
        override suspend fun commitGeneration(sessionId: String, message: ChatMessage, timedWorldInfo: Map<String, Int>) = Unit
        override suspend fun switchAlternative(sessionId: String, timestamp: Long, direction: Int) = false
        override suspend fun deleteMessage(sessionId: String, timestamp: Long) = Unit
        override suspend fun deleteAllVersions(sessionId: String, timestamp: Long) = Unit
        override suspend fun editMessage(sessionId: String, timestamp: Long, content: String) = Unit
        override suspend fun truncateAfter(sessionId: String, timestamp: Long) = Unit
        override suspend fun updateTitle(sessionId: String, title: String) = Unit
        override suspend fun updatePinned(sessionId: String, pinned: Boolean) = Unit
        override suspend fun saveLocalVariables(sessionId: String, variables: Map<String, String>) = Unit
        override suspend fun collectUnusedAttachments() = Unit
    }
}
