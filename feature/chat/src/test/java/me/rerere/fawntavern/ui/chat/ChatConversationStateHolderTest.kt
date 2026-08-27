package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.chat.MsgAlt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConversationStateHolderTest {
    @Test
    fun changingSessionClearsTransientOverlays() {
        val holder = ChatConversationStateHolder()
        holder.replaceCurrent(session("first"))
        holder.putOverlay(message(timestamp = 1L, content = "streaming"))

        holder.replaceCurrent(session("second"))

        assertEquals("second", holder.current?.id)
        assertTrue(holder.overlays.isEmpty())
    }

    @Test
    fun refreshingSameSessionKeepsTransientOverlays() {
        val holder = ChatConversationStateHolder()
        holder.replaceCurrent(session("same"))
        holder.putOverlay(message(timestamp = 1L, content = "streaming"))

        holder.replaceCurrent(session("same", message(timestamp = 1L, content = "persisted")))

        assertEquals("streaming", holder.overlays[1L]?.content)
    }

    @Test
    fun stalePersistenceResultDoesNotReplaceNewerOverlay() {
        val original = message(
            timestamp = 1L,
            content = "first",
            alternatives = listOf(MsgAlt(content = "first"), MsgAlt(content = "second")),
        )
        val holder = ChatConversationStateHolder()
        holder.replaceCurrent(session("chat", original))
        val optimistic = holder.switchAlternative(1L, 1)
        holder.putOverlay(holder.overlays.getValue(1L).copy(content = "newer edit"))

        holder.reconcileMessage(
            sessionId = "chat",
            timestamp = 1L,
            fresh = session("chat", original.copy(content = "second", altIdx = 1)),
            expectedOverlay = optimistic,
        )

        assertEquals("newer edit", holder.overlays[1L]?.content)
    }

    @Test
    fun switchAlternativeReturnsNullWhenMessageIsMissing() {
        val holder = ChatConversationStateHolder()

        assertNull(holder.switchAlternative(timestamp = 404L, direction = 1))
    }

    private fun session(id: String, vararg messages: ChatMessage) = ChatSession(
        id = id,
        messages = messages.toList(),
    )

    private fun message(
        timestamp: Long,
        content: String,
        alternatives: List<MsgAlt> = emptyList(),
    ) = ChatMessage(
        role = "assistant",
        content = content,
        ts = timestamp,
        alts = alternatives,
    )
}
