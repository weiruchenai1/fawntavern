package me.rerere.fawntavern.ui.worldbook

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.fawntavern.data.worldbook.WorldBookEntry

/** Serializes saves and drops snapshots superseded before their write starts. */
internal class WorldBookSaveCoordinator(
    private val save: suspend (List<WorldBookEntry>) -> Unit,
) {
    private val mutex = Mutex()
    private val latestRevision = AtomicLong()

    class Request internal constructor(
        internal val revision: Long,
        internal val entries: List<WorldBookEntry>,
    )

    fun request(entries: List<WorldBookEntry>): Request =
        Request(latestRevision.incrementAndGet(), entries)

    suspend fun saveLatest(request: Request) {
        mutex.withLock {
            if (request.revision != latestRevision.get()) return
            save(request.entries)
        }
    }
}
