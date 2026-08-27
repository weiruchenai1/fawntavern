package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.domain.chat.ChatSessionCoordinator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 协调会话操作、当前会话和提示上下文状态。 */
internal class ChatSessionActionCoordinator(
    private val scope: CoroutineScope,
    private val sessions: ChatSessionCoordinator,
    private val resources: ChatPromptContextDataSource,
    private val conversation: ChatConversationStateHolder,
    private val promptContext: ChatPromptContextStateHolder,
    private val newChatOnCharacterSwitch: () -> Boolean,
    private val newChatOnDelete: () -> Boolean,
) {
    fun open(sessionId: String) {
        scope.launch {
            sessions.open(sessionId)?.let(conversation::replaceCurrent)
        }
    }

    fun createNew() {
        val current = conversation.current
        if (current != null && current.messages.none { it.role == "user" }) return
        scope.launch {
            conversation.replaceCurrent(
                sessions.create(
                    card = promptContext.card,
                    charFile = current?.charFile.orEmpty(),
                    charName = current?.charName.orEmpty(),
                    persist = true,
                ),
            )
        }
    }

    fun openCharacter(fileName: String, displayName: String) {
        scope.launch {
            if (!newChatOnCharacterSwitch()) {
                val existing = conversation.sessions.firstOrNull { it.charFile == fileName }
                if (existing != null) {
                    conversation.replaceCurrent(sessions.open(existing.id) ?: existing)
                    return@launch
                }
            }
            val card = if (fileName.isBlank()) null else resources.loadCard(fileName)
            promptContext.replaceCard(card)
            conversation.replaceCurrent(
                sessions.create(card, fileName, displayName, persist = false),
            )
        }
    }

    fun delete(sessionId: String) {
        scope.launch {
            val replacement = sessions.delete(
                id = sessionId,
                currentSession = conversation.current,
                currentCard = promptContext.card,
                newChatOnDeleteTopic = newChatOnDelete(),
            )
            if (conversation.current?.id == sessionId) conversation.replaceCurrent(replacement)
        }
    }

    fun rename(sessionId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        scope.launch {
            sessions.rename(sessionId, trimmed)
            conversation.updateCurrent(sessionId) { it.copy(title = trimmed) }
        }
    }

    fun setPinned(sessionId: String, pinned: Boolean) {
        scope.launch {
            sessions.setPinned(sessionId, pinned)
            conversation.updateCurrent(sessionId) { it.copy(pinned = pinned) }
        }
    }
}
