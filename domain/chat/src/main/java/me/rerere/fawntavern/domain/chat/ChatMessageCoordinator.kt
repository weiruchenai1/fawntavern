package me.rerere.fawntavern.domain.chat

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.fawntavern.data.chat.ChatSession

/** Serializes per-session message mutations and reconciles the persisted session. */
class ChatMessageCoordinator(
    private val repository: ChatDataRepository,
) {
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

    suspend fun updateMessage(session: ChatSession, ts: Long, content: String): ChatSession? = mutate(session) {
        repository.editMessage(session.id, ts, content)
    }

    private suspend fun mutate(
        session: ChatSession,
        operation: suspend () -> Unit,
    ): ChatSession? = mutationMutexes.getOrPut(session.id) { Mutex() }.withLock {
        if (repository.get(session.id) == null) repository.save(session)
        operation()
        repository.get(session.id)
    }
}
