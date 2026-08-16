package me.rerere.fawntavern.data.api

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import kotlinx.coroutines.runBlocking

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
    fun responsesApiParsesTextReasoningToolsRawBlocksAndUsage() {
        enqueueSse(
            """{"type":"response.reasoning_summary_text.delta","delta":"think"}""",
            """{"type":"response.output_text.delta","delta":"answer"}""",
            """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"item-1","call_id":"call-1","name":"search_web","arguments":""}}""",
            """{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"query\":"}""",
            """{"type":"response.function_call_arguments.done","output_index":1,"arguments":"{\"query\":\"fawn\"}"}""",
            """{"type":"response.completed","response":{"usage":{"input_tokens":14,"output_tokens":6},"output":[{"type":"reasoning","id":"reasoning-1","encrypted_content":"encrypted","summary":[{"type":"summary_text","text":"think"}]},{"type":"function_call","id":"item-1","call_id":"call-1","name":"search_web","arguments":"{\"query\":\"fawn\"}"}]}}""",
        )
        val deltas = mutableListOf<Pair<String, String>>()

        val end = OpenAiAdapter.stream(
            provider = provider("openai").copy(useResponseApi = true),
            model = ModelInfo("model"),
            messages = listOf(ApiMessage("system", "system"), ApiMessage("user", "hello")),
            params = null,
            tools = listOf(ToolSpec("search_web", "Search", "{\"type\":\"object\"}")),
            onDelta = { content, reasoning -> deltas += content to reasoning },
            stopped = {},
            onCall = {},
        )

        val request = server.takeRequest()
        val requestBody = JSONObject(request.body.utf8())
        assertTrue(request.requestLine.startsWith("POST /v1/responses "))
        assertEquals("system", requestBody.getString("instructions"))
        assertEquals(listOf("" to "think", "answer" to ""), deltas)
        assertEquals("call-1", end.toolCalls.single().id)
        assertEquals("search_web", end.toolCalls.single().name)
        assertEquals("{\"query\":\"fawn\"}", end.toolCalls.single().arguments)
        assertEquals(14, end.promptTokens)
        assertEquals(6, end.completionTokens)
        assertEquals("reasoning", JSONArray(end.rawBlocks).getJSONObject(0).getString("type"))
    }

    @Test
    fun responsesApiConnectionToolTestUsesNonStreamingEndpoint() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("""{"output":[{"type":"function_call","call_id":"call-1","name":"get_current_time","arguments":"{}"}],"usage":{"input_tokens":4,"output_tokens":1}}""")
                .build(),
        )
        val model = ModelInfo("model")
        val provider = provider("openai").copy(
            useResponseApi = true,
            models = listOf(model),
        )

        val result = ConnectionTester.testToolCall(provider, model.id)

        assertEquals("get_current_time", result.toolName)
        assertEquals("{}", result.args)
        assertTrue(server.takeRequest().requestLine.startsWith("POST /v1/responses "))
    }

    @Test
    fun responsesApiUsesDashScopeCompatibleEndpoint() {
        val provider = ApiProvider(
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            useResponseApi = true,
        )

        assertEquals(
            "https://dashscope.aliyuncs.com/api/v2/apps/protocols/compatible-mode/v1/responses",
            OpenAiResponsesAdapter.endpoint(provider),
        )
    }

    @Test
    fun openAiRoutesImageOutputModelToImageGenerationEndpoint() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body(
                    JSONObject().put("data", JSONArray().put(JSONObject()
                        .put("b64_json", Base64.getEncoder().encodeToString(pngHeader)))).toString(),
                )
                .build(),
        )

        val end = OpenAiAdapter.stream(
            provider = provider("openai"),
            model = ModelInfo(
                "image-model",
                outputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            ),
            messages = listOf(
                ApiMessage("system", "ignore this"),
                ApiMessage("user", "draw a fawn"),
            ),
            params = null,
            tools = emptyList(),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        val request = server.takeRequest()
        val requestBody = JSONObject(request.body.utf8())
        assertTrue(request.requestLine.startsWith("POST /v1/images/generations "))
        assertEquals("image-model", requestBody.getString("model"))
        assertEquals("draw a fawn", requestBody.getString("prompt"))
        assertTrue(end.generatedImages.single().bytes.contentEquals(pngHeader))
        assertEquals("image/png", end.generatedImages.single().mimeType)
    }

    @Test
    fun openAiParsesGeneratedImageUrls() {
        val expected = GeneratedImage(byteArrayOf(1, 2, 3), "image/webp")
        val urls = mutableListOf<String>()

        val images = OpenAiAdapter.parseGeneratedImages(
            JSONObject().put("data", JSONArray().put(JSONObject().put("url", "https://example.com/a.webp"))),
        ) { url ->
            urls += url
            expected
        }

        assertEquals(listOf("https://example.com/a.webp"), urls)
        assertTrue(images.single().bytes.contentEquals(expected.bytes))
        assertEquals("image/webp", images.single().mimeType)
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
