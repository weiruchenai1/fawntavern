package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import me.rerere.fawntavern.data.chat.ChatDataRepository
import me.rerere.fawntavern.data.chat.ChatSession

/**
 * Persistence boundary for single-message mutations.
 *
 * The ViewModel owns optimistic UI overlays; this coordinator owns the Room sequencing required
 * by those overlays: an in-memory greeting-only session is persisted first, then the mutation is
 * applied, and finally the authoritative session is read back for reconciliation.
 */
internal class ChatMessageCoordinator(private val repository: ChatDataRepository) {
    // UI actions can arrive faster than Room writes. Serialize mutations so a late completion
    // cannot overwrite a newer version switch/edit/delete with stale session data.
    private val mutationMutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun switchAlt(session: ChatSession, ts: Long, dir: Int): ChatSession? = mutate(session) {
        repository.switchAlternative(session.id, ts, dir)
    }

    suspend fun deleteMessage(session: ChatSession, ts: Long): ChatSession? = mutate(session) {
        repository.deleteMessage(session.id, ts)
    }

    suspend fun deleteAllVersions(session: ChatSession, ts: Long): ChatSession? = mutate(session) {
        repository.deleteAllVersions(session.id, ts)
    }

    suspend fun updateMessage(session: ChatSession, ts: Long, content: String): ChatSession? =
        mutate(session) {
            repository.editMessage(session.id, ts, content)
        }

    private suspend fun mutate(
        session: ChatSession,
        operation: suspend () -> Unit,
    ): ChatSession? = mutationMutexes.getOrPut(session.id) { Mutex() }.withLock {
        ensurePersisted(session)
        operation()
        repository.get(session.id)
    }

    private suspend fun ensurePersisted(session: ChatSession) {
        if (repository.get(session.id) == null) {
            repository.save(session)
        }
    }
}
