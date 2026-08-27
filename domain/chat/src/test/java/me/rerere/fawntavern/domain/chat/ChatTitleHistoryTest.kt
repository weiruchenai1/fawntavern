package me.rerere.fawntavern.domain.chat

import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatTitleHistoryTest {
    @Test
    fun titleHistoryUsesAtMostTwoPairs() {
        val session = ChatSession(messages = listOf(
            message("user", "u1"),
            message("assistant", "a1"),
            message("user", "u2"),
            message("assistant", "a2"),
            message("user", "u3"),
            message("assistant", "a3"),
        ))

        assertEquals(
            "User: u1\nFawn: a1\nUser: u2\nFawn: a2",
            buildTitleHistory(session, "User", "Fawn"),
        )
    }

    @Test
    fun titleHistoryRequiresCompleteConversation() {
        val session = ChatSession(messages = listOf(message("user", "question")))

        assertNull(buildTitleHistory(session, "User", "Fawn"))
    }

    private fun message(role: String, content: String) = ChatMessage(role = role, content = content)
}
