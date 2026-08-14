package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGenerationPlannerTest {
    @Test
    fun sendUsesEntireHistoryAndCreatesAssistantMessage() {
        val messages = listOf(ChatMessage(role = "user", content = "hello", ts = 10))

        val plan = ChatGenerationPlanner.create(
            ChatSession(messages = messages),
            modelId = "model",
            mode = ChatGenerationMode.SEND,
            targetTimestamp = null,
        )!!

        assertEquals(messages, plan.buildHistory)
        assertEquals(messages, plan.promptHistory)
        assertEquals("assistant", plan.message.role)
        assertEquals("model", plan.message.model)
        assertTrue(plan.updateTimedWorldInfo)
    }

    @Test
    fun regenerateCutsPromptBeforeTargetButScansTarget() {
        val user = ChatMessage(role = "user", content = "hello", ts = 10)
        val assistant = ChatMessage(role = "assistant", content = "old", ts = 20)
        val later = ChatMessage(role = "user", content = "later", ts = 30)

        val plan = ChatGenerationPlanner.create(
            ChatSession(messages = listOf(user, assistant, later)),
            modelId = "new-model",
            mode = ChatGenerationMode.REGENERATE,
            targetTimestamp = assistant.ts,
        )!!

        assertEquals(listOf(user, assistant), plan.buildHistory)
        assertEquals(listOf(user), plan.promptHistory)
        assertEquals(assistant.ts, plan.message.ts)
        assertEquals("new-model", plan.message.model)
        assertFalse(plan.updateTimedWorldInfo)
    }

    @Test
    fun regenerateRejectsMissingOrGreetingOnlyTarget() {
        val greeting = ChatMessage(role = "assistant", content = "welcome", ts = 10)
        val session = ChatSession(messages = listOf(greeting))

        assertNull(
            ChatGenerationPlanner.create(
                session,
                "model",
                ChatGenerationMode.REGENERATE,
                greeting.ts,
            )
        )
        assertNull(
            ChatGenerationPlanner.create(
                session,
                "model",
                ChatGenerationMode.REGENERATE,
                999,
            )
        )
    }
}
