package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.domain.chat.ChatMessageCoordinator

/** 乐观更新只由同一次操作的结果校准，失败时重新读取持久化内容。 */
class ChatMessageMutationCoordinator(
    private val scope: CoroutineScope,
    private val persistence: ChatMessageCoordinator,
    private val conversation: ChatConversationStateHolder,
    private val onFailure: (Exception) -> Unit = {},
    private val onCommitted: (String, Long) -> Unit = { _, _ -> },
) {
    private var revision = 0L
    private val pending = mutableMapOf<Pair<String, Long>, Long>()

    fun switchAlternative(message: ChatMessage, direction: Int) {
        val session = conversation.current ?: return
        conversation.switchAlternative(message, direction) ?: return
        mutate(session, message.ts, "message_swiped") {
            persistence.switchAlt(session, message.ts, direction)
        }
    }

    fun updateMessage(message: ChatMessage, content: String) {
        val session = conversation.current ?: return
        val current = conversation.overlays[message.ts] ?: message
        conversation.putOverlay(current.copy(content = content))
        mutate(session, message.ts, "message_edited") {
            persistence.updateMessage(session, message.ts, content)
        }
    }

    fun deleteMessage(timestamp: Long) = delete(timestamp, allVersions = false)

    fun deleteAllVersions(timestamp: Long) = delete(timestamp, allVersions = true)

    private fun delete(timestamp: Long, allVersions: Boolean) {
        val session = conversation.current ?: return
        mutate(session, timestamp, "message_deleted") {
            val metadata = if (allVersions) persistence.deleteAllVersions(session, timestamp)
            else persistence.deleteMessage(session, timestamp)
            metadata?.let { fresh ->
                conversation.updateCurrent(session.id) {
                    it.copy(
                        totalMessageCount = fresh.totalMessageCount,
                        messageTimestamps = fresh.messageTimestamps,
                    )
                }
            }
            persistence.latestMessage(session.id, timestamp)
        }
    }

    private fun mutate(
        session: ChatSession,
        timestamp: Long,
        event: String,
        operation: suspend () -> ChatMessage?,
    ) {
        val key = session.id to timestamp
        val request = ++revision
        pending[key] = request
        scope.launch {
            try {
                val fresh = operation()
                if (pending[key] == request) conversation.reconcileOverlay(session.id, timestamp, fresh)
                if (conversation.current?.id == session.id) onCommitted(event, timestamp)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val fresh = try {
                    persistence.latestMessage(session.id, timestamp)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (recoveryError: Exception) {
                    error.addSuppressed(recoveryError)
                    null
                }
                if (pending[key] == request) conversation.reconcileOverlay(session.id, timestamp, fresh)
                onFailure(error)
            } finally {
                if (pending[key] == request) pending.remove(key)
            }
        }
    }
}
