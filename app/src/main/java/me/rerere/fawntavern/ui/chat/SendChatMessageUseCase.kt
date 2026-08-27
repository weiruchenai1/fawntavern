package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.CancellationException
import me.rerere.fawntavern.core.diagnostics.SafeLog
import me.rerere.fawntavern.data.chat.ChatDataRepository
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.domain.ConversationOps

internal sealed interface SendChatMessageResult {
    data object Completed : SendChatMessageResult
    data object AttachmentFailed : SendChatMessageResult

    data class Failed(
        val error: Exception,
        val restoredSession: ChatSession?,
        val retainedTimestamps: Set<Long>?,
        val rollbackFailed: Boolean,
    ) : SendChatMessageResult
}

/** Persists a user message and runs its generation as one recoverable workflow. */
internal class SendChatMessageUseCase(
    private val repository: ChatDataRepository,
    private val attachments: ChatAttachmentCoordinator,
) {
    suspend operator fun invoke(
        currentSession: () -> ChatSession?,
        text: String,
        pendingAttachments: List<Attachment>,
        onPrepared: (ChatSession, ChatMessage) -> Unit,
        generate: suspend (String) -> Unit,
    ): SendChatMessageResult {
        var originalSession: ChatSession? = null
        var createdNewSession = false
        try {
            val persistedAttachments = attachments.persist(pendingAttachments)
                ?: return SendChatMessageResult.AttachmentFailed
            val inMemorySession = currentSession()
            val existing = inMemorySession?.id?.let { repository.get(it) }
            val source = existing ?: inMemorySession ?: ChatSession()
            originalSession = source
            createdNewSession = existing == null
            val prepared = ConversationOps.appendUserMessage(
                source,
                text,
                persistedAttachments.images,
                persistedAttachments.files,
            )
            val userMessage = prepared.messages.last()
            onPrepared(prepared, userMessage)
            if (existing != null) repository.putMessage(prepared.id, userMessage)
            else repository.save(prepared)
            generate(prepared.id)
            return SendChatMessageResult.Completed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val before = originalSession
            if (before == null) {
                runCatching { attachments.collectUnused() }
                return SendChatMessageResult.Failed(error, null, null, rollbackFailed = false)
            }

            val rolledBack = runCatching {
                if (createdNewSession) repository.delete(before.id)
                else repository.save(before)
            }.onFailure { rollbackError ->
                error.addSuppressed(rollbackError)
                SafeLog.error(TAG, "send_rollback_failed", rollbackError)
            }.isSuccess
            if (rolledBack) {
                return SendChatMessageResult.Failed(
                    error = error,
                    restoredSession = before,
                    retainedTimestamps = before.messages.mapTo(HashSet()) { it.ts },
                    rollbackFailed = false,
                )
            }

            var refreshed: ChatSession? = null
            runCatching { repository.get(before.id) }
                .onSuccess { refreshed = it }
                .onFailure { refreshError ->
                    error.addSuppressed(refreshError)
                    SafeLog.error(TAG, "send_rollback_refresh_failed", refreshError)
                }
            return SendChatMessageResult.Failed(
                error = error,
                restoredSession = refreshed,
                retainedTimestamps = refreshed?.messages?.mapTo(HashSet()) { it.ts },
                rollbackFailed = true,
            )
        }
    }

    private companion object {
        const val TAG = "SendChatMessageUseCase"
    }
}
