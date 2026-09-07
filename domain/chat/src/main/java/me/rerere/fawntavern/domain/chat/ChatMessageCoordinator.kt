package me.rerere.fawntavern.domain.chat

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession

/** 按会话串行修改消息，并返回持久化结果供界面校准。 */
class ChatMessageCoordinator(
    private val repository: ChatDataRepository,
) {
    private val mutationMutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun latestMessage(sessionId: String, timestamp: Long): ChatMessage? =
        mutationMutexes.getOrPut(sessionId) { Mutex() }.withLock {
            repository.getMessage(sessionId, timestamp)
        }

    suspend fun switchAlt(session: ChatSession, ts: Long, dir: Int): ChatMessage? = mutate(session, ts) {
        repository.switchAlternative(session.id, ts, dir)
    }

    suspend fun deleteMessage(session: ChatSession, ts: Long): ChatSession? = mutateAndRefreshMetadata(session) {
        repository.deleteMessage(session.id, ts)
    }

    suspend fun deleteAllVersions(session: ChatSession, ts: Long): ChatSession? = mutateAndRefreshMetadata(session) {
        repository.deleteAllVersions(session.id, ts)
    }

    suspend fun updateMessage(session: ChatSession, ts: Long, content: String): ChatMessage? = mutate(session, ts) {
        repository.editMessage(session.id, ts, content)
    }

    private suspend fun mutate(
        session: ChatSession,
        timestamp: Long,
        operation: suspend () -> Unit,
    ): ChatMessage? = mutationMutexes.getOrPut(session.id) { Mutex() }.withLock {
        if (repository.getMetadata(session.id) == null) repository.save(session)
        operation()
        repository.getMessage(session.id, timestamp)
    }

    private suspend fun mutateAndRefreshMetadata(
        session: ChatSession,
        operation: suspend () -> Unit,
    ): ChatSession? = mutationMutexes.getOrPut(session.id) { Mutex() }.withLock {
        if (repository.getMetadata(session.id) == null) repository.save(session)
        operation()
        repository.getMetadata(session.id)
    }
}
