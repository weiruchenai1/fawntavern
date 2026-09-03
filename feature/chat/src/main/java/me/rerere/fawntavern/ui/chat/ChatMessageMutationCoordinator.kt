package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.domain.chat.ChatMessageCoordinator

/** 协调消息的乐观更新、串行持久化和结果对齐。 */
class ChatMessageMutationCoordinator(
    private val scope: CoroutineScope,
    private val persistence: ChatMessageCoordinator,
    private val conversation: ChatConversationStateHolder,
) {
    fun switchAlternative(message: ChatMessage, direction: Int) {
        val session = conversation.current ?: return
        val optimistic = conversation.switchAlternative(message, direction) ?: return
        scope.launch {
            persistence.switchAlt(session, message.ts, direction)?.let { fresh ->
                conversation.reconcileMessage(
                    session.id,
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
            persistence.deleteMessage(session, timestamp)?.let { metadata ->
                conversation.updateCurrent(session.id) {
                    it.copy(
                        totalMessageCount = metadata.totalMessageCount,
                        messageTimestamps = metadata.messageTimestamps,
                    )
                }
            }
        }
    }

    fun deleteAllVersions(timestamp: Long) {
        val session = conversation.current ?: return
        conversation.removeOverlay(timestamp)
        scope.launch {
            persistence.deleteAllVersions(session, timestamp)?.let { metadata ->
                conversation.updateCurrent(session.id) {
                    it.copy(
                        totalMessageCount = metadata.totalMessageCount,
                        messageTimestamps = metadata.messageTimestamps,
                    )
                }
            }
        }
    }

    fun updateMessage(message: ChatMessage, content: String) {
        val session = conversation.current ?: return
        val current = conversation.overlays[message.ts] ?: message
        conversation.putOverlay(current.copy(content = content))
        scope.launch {
            persistence.updateMessage(session, message.ts, content)?.let { fresh ->
                conversation.reconcileMessage(session.id, fresh)
            }
        }
    }
}
