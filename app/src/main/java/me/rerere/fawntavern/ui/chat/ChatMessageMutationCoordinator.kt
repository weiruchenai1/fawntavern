package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Coordinates optimistic message updates with serialized persistence and reconciliation. */
internal class ChatMessageMutationCoordinator(
    private val scope: CoroutineScope,
    private val persistence: ChatMessageCoordinator,
    private val conversation: ChatConversationStateHolder,
) {
    fun switchAlternative(timestamp: Long, direction: Int) {
        val session = conversation.current ?: return
        val optimistic = conversation.switchAlternative(timestamp, direction) ?: return
        scope.launch {
            persistence.switchAlt(session, timestamp, direction)?.let { fresh ->
                conversation.reconcileMessage(
                    session.id,
                    timestamp,
                    fresh,
                    expectedOverlay = optimistic,
                )
            }
        }
    }

    fun deleteMessage(timestamp: Long) {
        val session = conversation.current ?: return
        conversation.removeOverlay(timestamp)
        scope.launch {
            persistence.deleteMessage(session, timestamp)?.let { fresh ->
                conversation.reconcileMessage(session.id, timestamp, fresh)
            }
        }
    }

    fun deleteAllVersions(timestamp: Long) {
        val session = conversation.current ?: return
        conversation.removeOverlay(timestamp)
        scope.launch {
            persistence.deleteAllVersions(session, timestamp)?.let { fresh ->
                conversation.reconcileMessage(session.id, timestamp, fresh)
            }
        }
    }

    fun updateMessage(timestamp: Long, content: String) {
        val session = conversation.current ?: return
        val current = conversation.overlays[timestamp]
            ?: session.messages.firstOrNull { it.ts == timestamp }
        if (current != null) conversation.putOverlay(current.copy(content = content))
        scope.launch {
            persistence.updateMessage(session, timestamp, content)?.let { fresh ->
                conversation.reconcileMessage(session.id, timestamp, fresh)
            }
        }
    }
}
