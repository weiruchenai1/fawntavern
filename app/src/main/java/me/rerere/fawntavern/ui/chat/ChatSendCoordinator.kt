package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.core.diagnostics.SafeLog
import me.rerere.fawntavern.data.api.ApiProvider

internal enum class ChatSendOutcome { STARTED, NO_MODEL, SKIPPED, FILE_TOO_LARGE }

internal sealed interface ChatSendFailure {
    data object Attachment : ChatSendFailure
    data class Send(val error: Exception, val rollbackFailed: Boolean) : ChatSendFailure
}

/** Coordinates draft validation, user-message persistence, generation, and rollback UI state. */
internal class ChatSendCoordinator(
    private val input: ChatInputStateHolder,
    private val conversation: ChatConversationStateHolder,
    private val promptContext: ChatPromptContextStateHolder,
    private val attachments: ChatAttachmentCoordinator,
    private val messageMutations: ChatMessageMutationCoordinator,
    private val sendMessage: SendChatMessageUseCase,
    private val generation: ChatGenerationOrchestrator,
    private val resolveModel: () -> Pair<ApiProvider, String>?,
    private val onFailure: (ChatSendFailure) -> Unit,
) {
    fun send(): ChatSendOutcome {
        if (generation.isRunning) return ChatSendOutcome.SKIPPED
        if (!promptContext.isLoadedFor(conversation.current?.charFile.orEmpty())) {
            return ChatSendOutcome.SKIPPED
        }
        val text = input.text.trim()
        val pendingAttachments = input.attachments
        if (text.isBlank() && pendingAttachments.isEmpty()) return ChatSendOutcome.SKIPPED

        val editingTimestamp = input.editingTimestamp
        if (editingTimestamp != null) {
            if (text.isBlank()) return ChatSendOutcome.SKIPPED
            messageMutations.updateMessage(editingTimestamp, text)
            input.finishEditing()
            return ChatSendOutcome.STARTED
        }

        val (provider, modelId) = resolveModel() ?: return ChatSendOutcome.NO_MODEL
        if (attachments.hasOversizedFile(pendingAttachments)) {
            return ChatSendOutcome.FILE_TOO_LARGE
        }
        input.clearDraft()
        generation.launch {
            when (val result = sendMessage(
                currentSession = { conversation.current },
                text = text,
                pendingAttachments = pendingAttachments,
                onPrepared = { prepared, userMessage ->
                    conversation.replaceCurrent(prepared)
                    conversation.putOverlay(userMessage)
                },
                generate = { sessionId ->
                    generation.generate(
                        sessionId,
                        provider,
                        modelId,
                        ChatGenerationMode.SEND,
                        null,
                    )
                },
            )) {
                SendChatMessageResult.Completed -> Unit
                SendChatMessageResult.AttachmentFailed -> {
                    input.restoreDraft(text, pendingAttachments)
                    onFailure(ChatSendFailure.Attachment)
                }
                is SendChatMessageResult.Failed -> {
                    result.restoredSession?.let { restored ->
                        if (conversation.current?.id == restored.id) conversation.replaceCurrent(restored)
                    }
                    result.retainedTimestamps?.let(conversation::retainOverlays)
                    input.restoreDraft(text, pendingAttachments)
                    SafeLog.error(TAG, "send_message_failed", result.error)
                    onFailure(ChatSendFailure.Send(result.error, result.rollbackFailed))
                }
            }
        }
        return ChatSendOutcome.STARTED
    }

    private companion object {
        const val TAG = "ChatSendCoordinator"
    }
}
