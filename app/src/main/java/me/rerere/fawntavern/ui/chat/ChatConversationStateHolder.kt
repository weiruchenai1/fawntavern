package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.domain.ConversationOps

/** Owns the active conversation, its summaries, and transient message overlays. */
internal class ChatConversationStateHolder {
    var sessions by mutableStateOf<List<ChatSession>>(emptyList())
        private set

    var current by mutableStateOf<ChatSession?>(null)
        private set

    var overlays by mutableStateOf<Map<Long, ChatMessage>>(emptyMap())
        private set

    fun setSessions(values: List<ChatSession>) {
        sessions = values
    }

    fun setCurrent(value: ChatSession?) {
        if (current?.id != value?.id) overlays = emptyMap()
        current = value
    }

    fun updateCurrent(sessionId: String, transform: (ChatSession) -> ChatSession) {
        val value = current ?: return
        if (value.id == sessionId) current = transform(value)
    }

    fun putOverlay(message: ChatMessage) {
        overlays = overlays + (message.ts to message)
    }

    fun switchAlternative(timestamp: Long, direction: Int): ChatMessage? {
        val message = overlays[timestamp]
            ?: current?.messages?.firstOrNull { it.ts == timestamp }
            ?: return null
        val switched = ConversationOps.switchAltOne(message, direction) ?: return null
        putOverlay(switched)
        return switched
    }

    fun removeOverlay(timestamp: Long) {
        overlays = overlays - timestamp
    }

    fun retainOverlays(timestamps: Set<Long>) {
        overlays = overlays.filterKeys { it in timestamps }
    }

    fun reconcileMessage(
        sessionId: String,
        timestamp: Long,
        fresh: ChatSession,
        expectedOverlay: ChatMessage? = null,
    ) {
        if (current?.id != sessionId) return
        val currentOverlay = overlays[timestamp]
        current = fresh
        if (expectedOverlay != null && currentOverlay != expectedOverlay) return
        val row = fresh.messages.firstOrNull { it.ts == timestamp }
        overlays = if (row != null) overlays + (timestamp to row) else overlays - timestamp
    }
}
