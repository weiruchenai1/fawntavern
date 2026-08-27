package me.rerere.fawntavern.ui.character

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterOrderSaveCoordinatorTest {
    @Test
    fun newestOrderIsPersistedAfterAnOlderSaveAlreadyStarted() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val saved = mutableListOf<List<String>>()
        val coordinator = CharacterOrderSaveCoordinator(this, save = { names ->
            if (saved.isEmpty()) {
                firstStarted.complete(Unit)
                finishFirst.await()
            }
            saved += names
        })

        coordinator.request(listOf("A", "B"))
        firstStarted.await()
        coordinator.request(listOf("B", "A"))
        finishFirst.complete(Unit)

        withTimeout(1_000) {
            while (saved.size < 2) kotlinx.coroutines.yield()
        }
        assertEquals(listOf("B", "A"), saved.last())
    }
}
