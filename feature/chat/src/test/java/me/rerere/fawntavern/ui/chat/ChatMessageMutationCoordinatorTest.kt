package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.domain.chat.ChatMessageCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMessageMutationCoordinatorTest {
    private val message = ChatMessage(role = "assistant", content = "saved", ts = 1)
    private val session = ChatSession(id = "session", messages = listOf(message))

    @Test
    fun failedEditRestoresPersistedContentAndDoesNotPublishSuccess() = runTest {
        val repository = InMemoryChatRepository(session).apply { beforeEdit = { error("disk full") } }
        val state = ChatConversationStateHolder().apply { replaceCurrent(session) }
        val errors = mutableListOf<Exception>()
        val events = mutableListOf<String>()
        val coordinator = ChatMessageMutationCoordinator(
            this, ChatMessageCoordinator(repository), state, errors::add, { event, _ -> events += event },
        )
        coordinator.updateMessage(message, "unsaved")
        assertEquals("unsaved", state.overlays[message.ts]?.content)
        advanceUntilIdle()
        assertEquals(message, state.overlays[message.ts])
        assertEquals("disk full", errors.single().message)
        assertTrue(events.isEmpty())
    }

    @Test
    fun anEarlierFailureCannotUndoANewerSuccessfulEdit() = runTest {
        val release = CompletableDeferred<Unit>()
        val repository = InMemoryChatRepository(session).apply {
            beforeEdit = { content -> if (content == "first") { release.await(); error("first failed") } }
        }
        val state = ChatConversationStateHolder().apply { replaceCurrent(session) }
        val coordinator = ChatMessageMutationCoordinator(this, ChatMessageCoordinator(repository), state)
        coordinator.updateMessage(message, "first")
        runCurrent()
        coordinator.updateMessage(message, "second")
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals("second", state.overlays[message.ts]?.content)
        assertEquals("second", repository.getMessage(session.id, message.ts)?.content)
    }

    @Test
    fun twoFailedEditsRestoreTheDatabaseInsteadOfAnOlderOptimisticValue() = runTest {
        val release = CompletableDeferred<Unit>()
        val repository = InMemoryChatRepository(session).apply {
            beforeEdit = { release.await(); error("write failed") }
        }
        val state = ChatConversationStateHolder().apply { replaceCurrent(session) }
        val coordinator = ChatMessageMutationCoordinator(this, ChatMessageCoordinator(repository), state)
        coordinator.updateMessage(message, "first")
        runCurrent()
        coordinator.updateMessage(message, "second")
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(message, state.overlays[message.ts])
    }

    @Test
    fun completingAnEditInAnotherSessionDoesNotInjectItsOverlay() = runTest {
        val release = CompletableDeferred<Unit>()
        val repository = InMemoryChatRepository(session).apply { beforeEdit = { release.await() } }
        val state = ChatConversationStateHolder().apply { replaceCurrent(session) }
        val coordinator = ChatMessageMutationCoordinator(this, ChatMessageCoordinator(repository), state)
        coordinator.updateMessage(message, "edited")
        runCurrent()
        state.replaceCurrent(ChatSession(id = "another"))
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(state.overlays.isEmpty())
        assertEquals("edited", repository.getMessage(session.id, message.ts)?.content)
    }
}
