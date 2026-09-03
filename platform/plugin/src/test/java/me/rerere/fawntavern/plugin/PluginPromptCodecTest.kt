package me.rerere.fawntavern.plugin

import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.extension.PromptContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginPromptCodecTest {
    @Test
    fun contextContainsOnlyTrimmedMessageFields() {
        val session = ChatSession(
            id = "session-1",
            messages = listOf(
                ChatMessage(
                    role = "assistant",
                    content = "hello",
                    reasoning = "private reasoning",
                    images = listOf("private/path.png"),
                )
            ),
        )
        val encoded = PluginPromptCodec.encodeContext(
            PromptContext(session, charName = "A", userName = "B", extState = "{}")
        )

        assertTrue(encoded.contains("hello"))
        assertFalse(encoded.contains("private reasoning"))
        assertFalse(encoded.contains("private/path.png"))
    }

    @Test
    fun contributionIsValidatedAndClamped() {
        val envelope = JSONObject()
            .put("ok", true)
            .put(
                "value",
                JSONObject()
                    .put("preHistory", org.json.JSONArray().put(
                        JSONObject().put("role", "invalid").put("content", "prompt")
                    ))
                    .put("skipMessagesUpTo", Int.MAX_VALUE),
            )
            .toString()

        val result = PluginPromptCodec.decodeContribution(envelope, maxSkipIndex = 4)

        assertEquals("system", result.preHistory.single().role)
        assertEquals("prompt", result.preHistory.single().content)
        assertEquals(4, result.skipMessagesUpTo)
    }

    @Test
    fun stateIsTransferredAsACompleteJsonValue() {
        val payload = "x".repeat(20_000)
        val state = JSONObject().put("payload", payload).toString()
        val encoded = PluginPromptCodec.encodeContext(
            PromptContext(ChatSession(id = "session-1"), "A", "B", extState = state)
        )

        val decodedState = JSONObject(encoded).optJSONObject("extState")
        assertNotNull(decodedState)
        assertEquals(payload, decodedState!!.getString("payload"))
    }

    @Test
    fun oversizedEscapedHistoryDropsOldestMessagesWithinBinderLimit() {
        val messages = (0 until 20).map { index ->
            ChatMessage(
                role = if (index % 2 == 0) "user" else "assistant",
                content = "$index:" + "\u0001".repeat(8_000),
            )
        }
        val encoded = PluginPromptCodec.encodeContext(
            PromptContext(ChatSession(id = "session-1", messages = messages), "A", "B")
        )
        val outputMessages = JSONObject(encoded).getJSONArray("messages")

        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= 192 * 1024)
        assertTrue(outputMessages.length() < messages.size)
        assertTrue(outputMessages.getJSONObject(outputMessages.length() - 1).getString("content").startsWith("19:"))
    }
}
