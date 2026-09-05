package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.chat.MsgAlt
import me.rerere.fawntavern.data.speech.TtsUiState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatFrontendBindingsTest {
    private val first = ChatMessage(role = "user", ts = 100, content = "question", dataJson = "{\"score\":3}")
    private val last = ChatMessage(
        role = "assistant", ts = 200, content = "answer",
        alts = listOf(MsgAlt(content = "answer"), MsgAlt(content = "other", dataJson = "invalid")),
    )
    private val messages = listOf(first, last)
    private val ids = mapOf(100L to 40, 200L to 42)
    private val actions = mutableListOf<ChatAction>()

    @Test
    fun pagedIdsAndHiddenGapsAreSharedByContextSerializationAndActions() {
        val bindings = bindings()
        val json = JSONArray(bindings.messagesJson)
        assertEquals(40, json.getJSONObject(0).getInt("message_id"))
        assertEquals(42, json.getJSONObject(1).getInt("message_id"))
        val context = JSONObject(bindings.contextJson(last))
        assertEquals(42, context.getInt("messageId"))
        assertEquals(42, context.getInt("lastMessageId"))
        bindings.updateMessage(42, "edited")
        assertEquals(listOf(ChatAction.UpdateMessage(last, "edited")), actions)
    }

    @Test
    fun negativeIdsResolveWithinLoadedWindowAndMissingIdsDoNothing() {
        val bindings = bindings()
        bindings.updateMessage(-1, "last")
        bindings.updateMessage(-2, "first")
        bindings.updateMessage(-3, "out of bounds")
        bindings.updateMessage(41, "hidden row")
        assertEquals(listOf(ChatAction.UpdateMessage(last, "last"), ChatAction.UpdateMessage(first, "first")), actions)
    }

    @Test
    fun alternativeSelectionValidatesBoundsAndUsesRelativeDirection() {
        val bindings = bindings()
        bindings.selectAlternative(42, 1)
        bindings.selectAlternative(-1, -1)
        bindings.selectAlternative(-1, 2)
        bindings.selectAlternative(41, 0)
        assertEquals(listOf(ChatAction.SwitchAlternative(last, 1)), actions)
    }

    @Test
    fun serializationPreservesSingleVersionDataAndRecoversMalformedAlternativeData() {
        val json = JSONArray(bindings().messagesJson)
        val user = json.getJSONObject(0)
        assertEquals("Tester", user.getString("name"))
        assertEquals("question", user.getJSONArray("swipes").getString(0))
        assertEquals(3, user.getJSONArray("swipes_data").getJSONObject(0).getInt("score"))
        val assistant = json.getJSONObject(1)
        assertEquals("Character", assistant.getString("name"))
        assertEquals(2, assistant.getJSONArray("swipes_info").length())
        assertEquals(0, assistant.getJSONArray("swipes_data").getJSONObject(1).length())
    }

    private fun bindings() = ChatFrontendBindings(
        conversation = ChatConversationState(
            emptyList(), ChatSession(id = "session", charName = "Character"), null, null, emptyMap(), emptyList(),
        ),
        profile = ChatProfileState("Tester", null, null, TtsUiState()),
        messages = messages,
        messageIds = ids,
        messagesJson = encodeChatFrontendMessages(messages, ids, "Tester", "Character"),
        localVariablesJson = "{}", globalVariablesJson = "{}", userAvatar = "", characterAvatar = "",
        onAction = actions::add,
    )
}
