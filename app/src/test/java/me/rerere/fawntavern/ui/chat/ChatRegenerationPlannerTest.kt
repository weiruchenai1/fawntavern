package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRegenerationPlannerTest {
    @Test
    fun greetingCannotBeRegenerated() {
        val greeting = ChatMessage(role = "assistant", ts = 10)

        assertNull(
            ChatRegenerationPlanner.forAssistant(
                ChatSession(messages = listOf(greeting)),
                greeting.ts,
            ),
        )
    }

    @Test
    fun assistantAfterUserCreatesRegenerationPlan() {
        val user = ChatMessage(role = "user", ts = 10)
        val assistant = ChatMessage(role = "assistant", ts = 20)

        assertEquals(
            ChatRegenerationPlan.Regenerate(assistant.ts),
            ChatRegenerationPlanner.forAssistant(
                ChatSession(messages = listOf(user, assistant)),
                assistant.ts,
            ),
        )
    }

    @Test
    fun userWithAssistantRegeneratesFollowingAssistant() {
        val user = ChatMessage(role = "user", ts = 10)
        val assistant = ChatMessage(role = "assistant", ts = 20)

        assertEquals(
            ChatRegenerationPlan.Regenerate(assistant.ts),
            ChatRegenerationPlanner.afterUser(
                ChatSession(messages = listOf(user, assistant)),
                user.ts,
            ),
        )
    }

    @Test
    fun userWithoutAssistantTruncatesBeforeSending() {
        val user = ChatMessage(role = "user", ts = 10)

        assertEquals(
            ChatRegenerationPlan.TruncateAndSend(user.ts),
            ChatRegenerationPlanner.afterUser(
                ChatSession(messages = listOf(user)),
                user.ts,
            ),
        )
    }
}
