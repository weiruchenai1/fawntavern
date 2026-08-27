package me.rerere.fawntavern.ui.worldbook

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rerere.fawntavern.data.worldbook.WorldBookEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldBookSaveCoordinatorTest {
    @Test
    fun serializesWritesAndPersistsNewestSnapshotLast() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        val coordinator = WorldBookSaveCoordinator { entries ->
            if (saved.isEmpty()) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            saved += entries.single().content
        }

        val firstRequest = coordinator.request(listOf(entry("first")))
        val first = async { coordinator.saveLatest(firstRequest) }
        firstStarted.await()
        val secondRequest = coordinator.request(listOf(entry("second")))
        val second = async { coordinator.saveLatest(secondRequest) }
        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf("first", "second"), saved)
    }

    private fun entry(content: String) = WorldBookEntry(
        id = 1,
        keys = listOf("key"),
        comment = "entry",
        content = content,
    )
}
