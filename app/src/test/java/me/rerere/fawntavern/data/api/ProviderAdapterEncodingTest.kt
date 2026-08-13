package me.rerere.fawntavern.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAdapterEncodingTest {
    private val call = ApiToolCall(
        id = "call-1",
        name = "search_web",
        arguments = "{\"query\":\"fawn\"}",
        result = "{\"items\":[1]}",
        extra = "signature",
    )

    @Test
    fun openAiEncodesAssistantCallAndToolResult() {
        val encoded = OpenAiAdapter.encodeMessage(ApiMessage("assistant", "", toolCalls = listOf(call)))

        assertEquals(2, encoded.size)
        assertEquals("assistant", encoded[0].getString("role"))
        assertEquals("search_web", encoded[0].getJSONArray("tool_calls")
            .getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("tool", encoded[1].getString("role"))
        assertEquals("call-1", encoded[1].getString("tool_call_id"))
    }

    @Test
    fun googleEncodesFunctionCallSignatureAndResponse() {
        val encoded = GoogleAdapter.encodeContents(ApiMessage("assistant", "", toolCalls = listOf(call)))

        assertEquals(2, encoded.size)
        val callPart = encoded[0].getJSONArray("parts").getJSONObject(0)
        assertEquals("signature", callPart.getString("thoughtSignature"))
        assertEquals("search_web", callPart.getJSONObject("functionCall").getString("name"))
        assertTrue(encoded[1].getJSONArray("parts").getJSONObject(0).has("functionResponse"))
    }

    @Test
    fun claudeEncodesToolUseAndToolResult() {
        val encoded = ClaudeAdapter.encodeMessage(ApiMessage("assistant", "", toolCalls = listOf(call)))

        assertEquals(2, encoded.size)
        assertEquals("tool_use", encoded[0].getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("tool_result", encoded[1].getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("call-1", encoded[1].getJSONArray("content").getJSONObject(0).getString("tool_use_id"))
    }

    @Test
    fun leadingSystemIsSeparatedAndConversationSystemRemainsForCallerToDowngrade() {
        val (system, rest) = splitLeadingSystem(listOf(
            ApiMessage("system", "first"),
            ApiMessage("system", "second"),
            ApiMessage("user", "hello"),
            ApiMessage("system", "injection"),
        ))

        assertEquals("first\n\nsecond", system)
        assertEquals(listOf("user", "system"), rest.map { it.role })
        assertFalse(rest.isEmpty())
    }
}
