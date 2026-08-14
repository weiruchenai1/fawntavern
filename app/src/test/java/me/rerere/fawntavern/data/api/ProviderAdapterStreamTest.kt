package me.rerere.fawntavern.data.api

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderAdapterStreamTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun openAiParsesDeltasToolFragmentsAndUsage() {
        enqueueSse(
            """{"choices":[{"delta":{"reasoning_content":"think"}}]}""",
            """{"choices":[{"delta":{"content":"answer"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"search_web","arguments":"{\"query\":"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"fawn\"}"}}]}}]}""",
            """{"usage":{"prompt_tokens":12,"completion_tokens":4},"choices":[]}""",
            "[DONE]",
        )
        val deltas = mutableListOf<Pair<String, String>>()

        val end = OpenAiAdapter.stream(
            provider = provider("openai"),
            model = ModelInfo("model"),
            messages = listOf(ApiMessage("user", "hello")),
            params = null,
            tools = emptyList(),
            onDelta = { content, reasoning -> deltas += content to reasoning },
            stopped = {},
            onCall = {},
        )

        assertEquals(listOf("" to "think", "answer" to ""), deltas)
        assertEquals("search_web", end.toolCalls.single().name)
        assertEquals("{\"query\":\"fawn\"}", end.toolCalls.single().arguments)
        assertEquals(12, end.promptTokens)
        assertEquals(4, end.completionTokens)
    }

    @Test
    fun googleSeparatesThoughtsAndParsesFunctionCall() {
        enqueueSse(
            """{"candidates":[{"content":{"parts":[{"thought":true,"text":"think"},{"text":"answer"},{"thoughtSignature":"sig","functionCall":{"name":"search_web","args":{"query":"fawn"}}}]}}],"usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":3,"thoughtsTokenCount":2}}""",
        )
        val deltas = mutableListOf<Pair<String, String>>()

        val end = GoogleAdapter.stream(
            provider = provider("google"),
            model = ModelInfo("gemini-test"),
            messages = listOf(ApiMessage("user", "hello")),
            params = null,
            tools = emptyList(),
            onDelta = { content, reasoning -> deltas += content to reasoning },
            stopped = {},
            onCall = {},
        )

        assertEquals(listOf("" to "think", "answer" to ""), deltas)
        assertEquals("search_web", end.toolCalls.single().name)
        assertEquals("sig", end.toolCalls.single().extra)
        assertEquals(8, end.promptTokens)
        assertEquals(5, end.completionTokens)
    }

    @Test
    fun claudeParsesThinkingSignatureToolFragmentsAndUsage() {
        enqueueSse(
            """{"type":"message_start","message":{"usage":{"input_tokens":9,"output_tokens":0}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"think"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"answer"}}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tool-1","name":"search_web"}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"fawn\"}"}}""",
            """{"type":"message_delta","usage":{"output_tokens":6}}""",
        )
        val deltas = mutableListOf<Pair<String, String>>()

        val end = ClaudeAdapter.stream(
            provider = provider("claude"),
            model = ModelInfo("claude-test"),
            messages = listOf(ApiMessage("user", "hello")),
            params = null,
            tools = emptyList(),
            onDelta = { content, reasoning -> deltas += content to reasoning },
            stopped = {},
            onCall = {},
        )

        assertEquals(listOf("" to "think", "answer" to ""), deltas)
        assertEquals("{\"query\":\"fawn\"}", end.toolCalls.single().arguments)
        assertEquals(9, end.promptTokens)
        assertEquals(6, end.completionTokens)
        val raw = JSONArray(end.rawBlocks)
        assertEquals("sig", raw.getJSONObject(0).getString("signature"))
        assertTrue(raw.toString().contains("tool_use"))
    }

    private fun provider(type: String) = ApiProvider(
        type = type,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "test-key",
    )

    private fun enqueueSse(vararg events: String) {
        val body = events.joinToString("\n\n") { "data: $it" } + "\n\n"
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(body)
                .build(),
        )
    }
}
