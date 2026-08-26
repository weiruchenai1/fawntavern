package me.rerere.fawntavern.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class ProviderAdapterEncodingTest {
    private val call = ApiToolCall(
        id = "call-1",
        name = "search_web",
        arguments = "{\"query\":\"fawn\"}",
        result = "{\"items\":[1]}",
        extra = "signature",
    )

    @Test
    fun openAiImageSizeUsesOfficialDiscreteSizes() {
        assertEquals("1024x1024", OpenAiAdapter.openAiImageSize("1:1"))
        assertEquals("1536x1024", OpenAiAdapter.openAiImageSize("21:9"))
        assertEquals("1024x1536", OpenAiAdapter.openAiImageSize("2:3"))
        assertEquals(null, OpenAiAdapter.openAiImageSize("auto"))
        assertEquals(null, OpenAiAdapter.openAiImageSize("invalid"))
    }

    @Test
    fun openAiImageQualityAcceptsOnlySupportedExplicitValues() {
        assertEquals("low", OpenAiAdapter.openAiImageQuality("LOW"))
        assertEquals("medium", OpenAiAdapter.openAiImageQuality("medium"))
        assertEquals("high", OpenAiAdapter.openAiImageQuality("high"))
        assertEquals(null, OpenAiAdapter.openAiImageQuality("auto"))
        assertEquals(null, OpenAiAdapter.openAiImageQuality("ultra"))
    }

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
    fun responsesApiUsesInstructionsFlatToolsAndToolOutputItems() {
        val provider = ApiProvider(
            baseUrl = "https://api.openai.com/v1",
            useResponseApi = true,
        )
        val rawReasoning = JSONArray().put(JSONObject()
            .put("type", "reasoning")
            .put("id", "reasoning-1")
            .put("encrypted_content", "encrypted"))
        val body = OpenAiResponsesAdapter.buildRequestBody(
            provider = provider,
            model = ModelInfo("gpt-test"),
            messages = listOf(
                ApiMessage("system", "system one"),
                ApiMessage("system", "system two"),
                ApiMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(call),
                    rawBlocks = rawReasoning.toString(),
                ),
            ),
            params = GenParams(maxTokens = 321),
            tools = listOf(ToolSpec("search_web", "Search", "{\"type\":\"object\"}")),
            stream = true,
        )

        assertEquals("system one\n\nsystem two", body.getString("instructions"))
        assertEquals(321, body.getInt("max_output_tokens"))
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("search_web", tool.getString("name"))
        assertFalse(tool.has("function"))
        val input = body.getJSONArray("input")
        assertEquals("reasoning", input.getJSONObject(0).getString("type"))
        assertEquals("function_call", input.getJSONObject(1).getString("type"))
        assertEquals("function_call_output", input.getJSONObject(2).getString("type"))
        assertEquals("call-1", input.getJSONObject(2).getString("call_id"))
    }

    @Test
    fun volcResponsesApiOmitsUnsupportedReasoningMetadata() {
        val body = OpenAiResponsesAdapter.buildRequestBody(
            provider = ApiProvider(baseUrl = "https://ark.cn-beijing.volces.com/api/v3"),
            model = ModelInfo("reasoner", abilities = listOf(ModelAbility.REASONING)),
            messages = listOf(ApiMessage("user", "hello")),
            params = GenParams(reasoning = ReasoningLevel.LOW),
            tools = emptyList(),
            stream = true,
        )

        assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
        assertFalse(body.getJSONObject("reasoning").has("summary"))
        assertFalse(body.has("include"))
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
