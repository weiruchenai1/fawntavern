package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGenerationCoordinatorTest {
    @Test
    fun rejectsConcurrentTaskAndReleasesAfterCompletion() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val release = CompletableDeferred<Unit>()
        val states = mutableListOf<Boolean>()
        val coordinator = ChatGenerationCoordinator(scope, {}, states::add)

        assertTrue(coordinator.launch { release.await() })
        assertTrue(coordinator.isRunning)
        assertFalse(coordinator.launch { error("must not run") })

        release.complete(Unit)
        while (coordinator.isRunning) yield()

        assertEquals(listOf(true, false), states)
        scope.cancel()
    }

    @Test
    fun releasesAfterFailureAndForwardsStop() = runBlocking {
        val failure = CompletableDeferred<Throwable>()
        val scope = CoroutineScope(coroutineContext + Job())
        var stopped = false
        val coordinator = ChatGenerationCoordinator(scope, { stopped = true }, {}, failure::complete)

        coordinator.stop()
        assertTrue(stopped)

        assertTrue(coordinator.launch { throw IllegalStateException("failure") })
        while (coordinator.isRunning) yield()
        assertFalse(coordinator.isRunning)
        assertEquals("failure", failure.await().message)
        scope.cancel()
    }
}
