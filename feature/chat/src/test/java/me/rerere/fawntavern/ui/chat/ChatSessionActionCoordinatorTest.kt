package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.domain.chat.ChatSessionCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionActionCoordinatorTest {
    @Test
    fun lateOldSelectionCannotReplaceTheNewSelectionOrEmitAnEvent() = runTest {
        val old = ChatSession(id = "old", charFile = "old-card")
        val latest = ChatSession(id = "latest", charFile = "latest-card")
        val release = CompletableDeferred<Unit>()
        val repository = object : InMemoryChatRepository(old, latest) {
            override suspend fun getMetadata(id: String): ChatSession? {
                if (id == old.id) withContext(NonCancellable) { release.await() }
                return super.getMetadata(id)
            }
        }
        val state = ChatConversationStateHolder()
        val selected = mutableListOf<String>()
        val coordinator = ChatSessionActionCoordinator(
            this, ChatSessionCoordinator(repository), EmptyPromptContextDataSource, state,
            ChatPromptContextStateHolder(), { false }, { false }, onSelected = selected::add,
        )
        coordinator.open(old.id)
        runCurrent()
        assertTrue(coordinator.isSelecting)
        coordinator.open(latest.id)
        runCurrent()
        assertEquals(latest.id, state.current?.id)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(latest.id, state.current?.id)
        assertEquals(listOf(latest.id), selected)
        assertFalse(coordinator.isSelecting)
    }

    @Test
    fun failureKeepsTheCurrentSessionAndAllowsRetry() = runTest {
        val original = ChatSession(id = "original")
        var fail = true
        val repository = object : InMemoryChatRepository(original) {
            override suspend fun getMetadata(id: String): ChatSession? {
                if (fail) error("read failed")
                return super.getMetadata(id)
            }
        }
        val state = ChatConversationStateHolder().apply { replaceCurrent(original) }
        val failures = mutableListOf<Exception>()
        val coordinator = ChatSessionActionCoordinator(
            this, ChatSessionCoordinator(repository), EmptyPromptContextDataSource, state,
            ChatPromptContextStateHolder(), { false }, { false }, onFailure = failures::add,
        )
        coordinator.open("missing")
        advanceUntilIdle()
        assertEquals(original, state.current)
        assertEquals("read failed", failures.single().message)
        assertFalse(coordinator.isSelecting)
        fail = false
        coordinator.open(original.id)
        advanceUntilIdle()
        assertEquals(original.id, state.current?.id)
    }

    @Test
    fun selectionDoesNotCommitAfterGenerationStarts() = runTest {
        val original = ChatSession(id = "original")
        val target = ChatSession(id = "target")
        val release = CompletableDeferred<Unit>()
        var generating = false
        val repository = object : InMemoryChatRepository(original, target) {
            override suspend fun getMetadata(id: String): ChatSession? {
                release.await()
                return super.getMetadata(id)
            }
        }
        val state = ChatConversationStateHolder().apply { replaceCurrent(original) }
        val coordinator = ChatSessionActionCoordinator(
            this, ChatSessionCoordinator(repository), EmptyPromptContextDataSource, state,
            ChatPromptContextStateHolder(), { false }, { false }, canSelect = { !generating },
        )
        coordinator.open(target.id)
        runCurrent()
        generating = true
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(original, state.current)
        assertFalse(coordinator.isSelecting)
    }
}
