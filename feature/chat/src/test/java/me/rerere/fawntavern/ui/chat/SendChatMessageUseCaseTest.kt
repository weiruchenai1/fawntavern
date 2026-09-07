package me.rerere.fawntavern.ui.chat

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.chat.MsgFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendChatMessageUseCaseTest {
    private val original = ChatSession(
        id = "session",
        messages = listOf(ChatMessage(role = "assistant", content = "Greeting", ts = 1)),
    )

    @Test
    fun userMessageIsPersistedBeforeGenerationAndStopAllowsTheNextSend() = runTest {
        val repository = InMemoryChatRepository(original)
        val send = SendChatMessageUseCase(repository, attachments())
        val stopSignal = CompletableDeferred<Unit>()
        val generation = ChatGenerationCoordinator(this, { stopSignal.complete(Unit) })
        val prepared = mutableListOf<ChatMessage>()
        var result: SendChatMessageResult? = null

        assertTrue(generation.launch {
            result = send({ original }, "First message", emptyList(), { _, message -> prepared += message }) { id ->
                assertEquals("First message", repository.get(id)?.messages?.last()?.content)
                generation.markTarget(100)
                stopSignal.await()
            }
        })
        runCurrent()
        assertEquals("First message", prepared.single().content)
        assertEquals(100L, generation.targetTimestamp)
        assertFalse(generation.launch { error("Concurrent send must be rejected") })

        generation.stop()
        advanceUntilIdle()
        assertSame(SendChatMessageResult.Completed, result)
        assertFalse(generation.isRunning)
        assertNull(generation.targetTimestamp)

        assertTrue(generation.launch {
            send({ original }, "Second message", emptyList(), { _, _ -> }) { id ->
                assertEquals(listOf("Greeting", "First message", "Second message"),
                    repository.get(id)?.messages?.map { it.content })
            }
        })
        advanceUntilIdle()
        assertFalse(generation.isRunning)
        assertTrue(repository.get(original.id)!!.messages.zipWithNext().all { (first, next) -> first.ts < next.ts })
    }

    @Test
    fun generationFailureRestoresTheExistingSessionAndItsVisibleTimestamps() = runTest {
        val repository = InMemoryChatRepository(original)
        val failure = IllegalStateException("Generation preparation failed")
        val result = SendChatMessageUseCase(repository, attachments())(
            { original }, "Unsent", emptyList(), { _, _ -> }, { throw failure },
        ) as SendChatMessageResult.Failed

        assertSame(failure, result.error)
        assertEquals(original, repository.get(original.id))
        assertEquals(original, result.restoredSession)
        assertEquals(setOf(1L), result.retainedTimestamps)
        assertFalse(result.rollbackFailed)
    }

    @Test
    fun failedFirstSendRemovesTheNewlyPersistedSession() = runTest {
        val repository = InMemoryChatRepository()
        val result = SendChatMessageUseCase(repository, attachments())(
            { original }, "Unsent", emptyList(), { _, _ -> }, { error("Generation preparation failed") },
        ) as SendChatMessageResult.Failed

        assertNull(repository.get(original.id))
        assertEquals(original, result.restoredSession)
        assertFalse(result.rollbackFailed)
    }

    @Test
    fun persistenceFailureNeverStartsGenerationAndRestoresTheOriginalSession() = runTest {
        val repository = object : InMemoryChatRepository(original) {
            override suspend fun putMessage(sessionId: String, message: ChatMessage) {
                error("Disk full")
            }
        }
        var started = false
        val result = SendChatMessageUseCase(repository, attachments())(
            { original }, "Unsent", emptyList(), { _, _ -> }, { started = true },
        ) as SendChatMessageResult.Failed

        assertFalse(started)
        assertEquals("Disk full", result.error.message)
        assertEquals(original, repository.get(original.id))
    }

    @Test
    fun cancellationKeepsThePersistedUserMessageAndPropagatesToTheCaller() = runTest {
        val repository = InMemoryChatRepository(original)
        val started = CompletableDeferred<Unit>()
        var completed = false
        val job = launch {
            SendChatMessageUseCase(repository, attachments())(
                { original }, "Persisted user message", emptyList(), { _, _ -> },
            ) {
                started.complete(Unit)
                awaitCancellation()
            }
            completed = true
        }
        started.await()
        job.cancelAndJoin()

        assertFalse(completed)
        assertEquals("Persisted user message", repository.get(original.id)?.messages?.last()?.content)
    }

    @Test
    fun cancellationDuringRollbackIsPropagated() = runTest {
        val cancellation = CancellationException("Scope closed during rollback")
        val repository = object : InMemoryChatRepository(original) {
            override suspend fun putMessage(sessionId: String, message: ChatMessage) {
                error("Write failed")
            }

            override suspend fun save(session: ChatSession) {
                throw cancellation
            }
        }
        try {
            SendChatMessageUseCase(repository, attachments())(
                { original }, "Unsent", emptyList(), { _, _ -> }, { error("Generation must not start") },
            )
            fail("Cancellation must be propagated")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private fun attachments() = ChatAttachmentCoordinator(object : ChatAttachmentDataSource {
        override fun isTooLarge(uri: Uri) = false
        override suspend fun persistImage(uri: Uri): String? = error("No attachments expected")
        override suspend fun persistFile(uri: Uri): MsgFile? = error("No attachments expected")
        override suspend fun collectUnused() = Unit
    })
}
