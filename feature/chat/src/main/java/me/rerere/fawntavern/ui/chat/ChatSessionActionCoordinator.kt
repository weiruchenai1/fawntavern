package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.domain.chat.ChatSessionCoordinator

/** 会话选择只接受最新请求；写操作串行提交并统一报告失败。 */
internal class ChatSessionActionCoordinator(
    private val scope: CoroutineScope,
    private val sessions: ChatSessionCoordinator,
    private val resources: ChatPromptContextDataSource,
    private val conversation: ChatConversationStateHolder,
    private val promptContext: ChatPromptContextStateHolder,
    private val newChatOnCharacterSwitch: () -> Boolean,
    private val newChatOnDelete: () -> Boolean,
    private val canSelect: () -> Boolean = { true },
    private val onFailure: (Exception) -> Unit = {},
    private val onSelected: (String) -> Unit = {},
) {
    private var selectionRevision = 0L
    private var selectionJob: Job? = null
    private val mutationMutex = Mutex()

    var isSelecting by mutableStateOf(false)
        private set

    fun open(sessionId: String) = select { sessions.open(sessionId) }

    fun createNew() {
        if (isSelecting) return
        val current = conversation.current
        val card = promptContext.card
        select {
            if (current != null) {
                val latest = sessions.loadFull(current.id) ?: current
                if (latest.messages.none { it.role == "user" }) return@select null
            }
            sessions.create(card, current?.charFile.orEmpty(), current?.charName.orEmpty(), persist = true)
        }
    }

    fun openCharacter(fileName: String, displayName: String) = select {
        val existing = if (newChatOnCharacterSwitch()) null
        else conversation.sessions.firstOrNull { it.charFile == fileName }
        if (existing != null) {
            sessions.open(existing.id)
        } else {
            val card = if (fileName.isBlank()) null else resources.loadCard(fileName)
            sessions.create(card, fileName, displayName, persist = false)
        }
    }

    fun delete(sessionId: String) {
        val expectedSelection = selectionRevision
        mutate {
            val replacement = sessions.delete(
                id = sessionId,
                currentSession = conversation.current,
                currentCard = promptContext.card,
                newChatOnDeleteTopic = newChatOnDelete(),
            )
            if (conversation.current?.id == sessionId && selectionRevision == expectedSelection) {
                conversation.replaceCurrent(replacement)
                onSelected(replacement?.id.orEmpty())
            }
        }
    }

    fun rename(sessionId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        mutate {
            sessions.rename(sessionId, trimmed)
            conversation.updateCurrent(sessionId) { it.copy(title = trimmed) }
        }
    }

    fun setPinned(sessionId: String, pinned: Boolean) = mutate {
        sessions.setPinned(sessionId, pinned)
        conversation.updateCurrent(sessionId) { it.copy(pinned = pinned) }
    }

    private fun select(load: suspend () -> ChatSession?) {
        if (!canSelect()) return
        val request = ++selectionRevision
        selectionJob?.cancel()
        isSelecting = true
        selectionJob = scope.launch {
            try {
                val selected = load() ?: return@launch
                currentCoroutineContext().ensureActive()
                if (selectionRevision == request && canSelect()) {
                    conversation.replaceCurrent(selected)
                    onSelected(selected.id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (selectionRevision == request) onFailure(error)
            } finally {
                if (selectionRevision == request) isSelecting = false
            }
        }
    }

    private fun mutate(operation: suspend () -> Unit) {
        scope.launch {
            try {
                mutationMutex.withLock { operation() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure(error)
            }
        }
    }
}
