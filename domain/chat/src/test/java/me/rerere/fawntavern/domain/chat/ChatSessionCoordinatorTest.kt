package me.rerere.fawntavern.domain.chat

import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionCoordinatorTest {
    @Test
    fun deleteCurrentPrefersAnotherSessionForSameCharacter() {
        val current = session("current", "fawn.json")
        val choice = chooseSessionAfterDelete(
            deletedId = "current",
            currentSession = current,
            summaries = listOf(session("other", "fawn.json"), session("different", "other.json")),
            newChatOnDeleteTopic = false,
        )

        assertEquals("other", choice.nextSessionId)
        assertTrue(choice.shouldCreateNew)
    }

    @Test
    fun deleteCurrentCreatesNewWhenPreferenceIsEnabled() {
        val choice = chooseSessionAfterDelete(
            deletedId = "current",
            currentSession = session("current", "fawn.json"),
            summaries = listOf(session("other", "fawn.json")),
            newChatOnDeleteTopic = true,
        )

        assertEquals(null, choice.nextSessionId)
        assertTrue(choice.shouldCreateNew)
    }

    @Test
    fun deleteOtherSessionDoesNotChangeCurrentSelection() {
        val choice = chooseSessionAfterDelete(
            deletedId = "other",
            currentSession = session("current", "fawn.json"),
            summaries = emptyList(),
            newChatOnDeleteTopic = false,
        )

        assertEquals(null, choice.nextSessionId)
        assertFalse(choice.shouldCreateNew)
    }

    private fun session(id: String, charFile: String) = ChatSession(
        id = id,
        charFile = charFile,
        charName = "Fawn",
        messages = listOf(ChatMessage(role = "user", content = "hello")),
    )
}
