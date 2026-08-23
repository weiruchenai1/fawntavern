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
    fun openAiCompatibleProviderUsesCustomApiPath() {
        enqueueSse("[DONE]")

        OpenAiAdapter.stream(
            provider = provider("openai").copy(apiPath = "/custom/chat"),
            model = ModelInfo("model"),
            messages = listOf(ApiMessage("user", "hello")),
            params = null,
            tools = emptyList(),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        assertTrue(server.takeRequest().requestLine.startsWith("POST /v1/custom/chat "))
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
        val requestBody = JSONObject(requireNotNull(request.body).utf8())
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
    fun geminiImageModelRequestsImageConfigAndParsesInlineData() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val encoded = Base64.getEncoder().encodeToString(png)
        enqueueSse(
            JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("inlineData", JSONObject()
                    .put("mimeType", "image/png").put("data", encoded))))))).toString(),
        )

        val end = GoogleAdapter.stream(
            provider = provider("google"),
            model = ModelInfo("gemini-3.1-flash-image", outputModalities = listOf(Modality.TEXT, Modality.IMAGE)),
            messages = listOf(ApiMessage("user", "draw a fawn")),
            params = GenParams(imageGeneration = ImageGenerationSettings(aspectRatio = "16:9", resolution = "2k")),
            tools = emptyList(),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        val requestBody = JSONObject(requireNotNull(server.takeRequest().body).utf8())
        val config = requestBody.getJSONObject("generationConfig")
        assertEquals("IMAGE", config.getJSONArray("responseModalities").getString(1))
        assertEquals("16:9", config.getJSONObject("imageConfig").getString("aspectRatio"))
        assertEquals("2K", config.getJSONObject("imageConfig").getString("imageSize"))
        assertTrue(end.generatedImages.single().bytes.contentEquals(png))
    }

    @Test
    fun geminiCombinesBuiltInAndCustomTools() {
        enqueueSse("{" +
            "\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]" +
            "}")

        GoogleAdapter.stream(
            provider = provider("google"),
            model = ModelInfo("gemini-3.7-flash", tools = setOf(BuiltInTool.SEARCH)),
            messages = listOf(ApiMessage("user", "search")),
            params = GenParams(toolChoice = ToolChoice.REQUIRED),
            tools = listOf(ToolSpec("search_web", "Search", "{\"type\":\"object\"}")),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        val body = JSONObject(requireNotNull(server.takeRequest().body).utf8())
        val encodedTools = body.getJSONArray("tools")
        assertTrue(encodedTools.getJSONObject(0).has("googleSearch"))
        assertTrue(encodedTools.getJSONObject(1).has("functionDeclarations"))
        assertEquals("ANY", body.getJSONObject("toolConfig")
            .getJSONObject("functionCallingConfig").getString("mode"))
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
    fun responsesApiUsesCustomPathForCompatibleProvider() {
        val provider = ApiProvider(
            type = "openai",
            baseUrl = "https://gateway.example.com/v1",
            apiPath = "/custom/responses",
            useResponseApi = true,
        )

        assertEquals(
            "https://gateway.example.com/v1/custom/responses",
            OpenAiResponsesAdapter.endpoint(provider),
        )
    }

    @Test
    fun officialOpenAiAlsoUsesEditableCustomPath() {
        val provider = ApiProvider(
            type = "openai",
            baseUrl = "https://api.openai.com/v1",
            apiPath = "/custom/responses",
            useResponseApi = true,
        )

        assertEquals(
            "https://api.openai.com/v1/custom/responses",
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
        val requestBody = JSONObject(requireNotNull(request.body).utf8())
        assertTrue(request.requestLine.startsWith("POST /v1/images/generations "))
        assertEquals("image-model", requestBody.getString("model"))
        assertEquals("draw a fawn", requestBody.getString("prompt"))
        assertTrue(end.generatedImages.single().bytes.contentEquals(pngHeader))
        assertEquals("image/png", end.generatedImages.single().mimeType)
    }

    @Test
    fun openAiImageRequestMapsSettingsToOfficialImageApiFields() {
        enqueueGeneratedImage()

        OpenAiAdapter.stream(
            provider = ApiProvider(type = "openai", baseUrl = server.url("/v1").toString()),
            model = ModelInfo(
                "gpt-image-1",
                outputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            ),
            messages = listOf(ApiMessage("user", "draw a fawn")),
            params = GenParams(
                imageGeneration = ImageGenerationSettings(
                    count = 2,
                    aspectRatio = "2:3",
                    resolution = "1k",
                    quality = "high",
                ),
            ),
            tools = emptyList(),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        val requestBody = JSONObject(requireNotNull(server.takeRequest().body).utf8())
        assertEquals(2, requestBody.getInt("n"))
        assertEquals("1024x1536", requestBody.getString("size"))
        assertEquals("high", requestBody.getString("quality"))
        assertTrue(!requestBody.has("aspect_ratio"))
        assertTrue(!requestBody.has("resolution"))
    }

    @Test
    fun openAiImageRequestOmitsSizeForAutomaticAspectRatio() {
        enqueueGeneratedImage()

        OpenAiAdapter.stream(
            provider = ApiProvider(type = "openai", baseUrl = server.url("/v1").toString()),
            model = ModelInfo("gpt-image-1", outputModalities = listOf(Modality.IMAGE)),
            messages = listOf(ApiMessage("user", "draw a fawn")),
            params = GenParams(imageGeneration = ImageGenerationSettings(aspectRatio = "auto")),
            tools = emptyList(),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        val requestBody = JSONObject(requireNotNull(server.takeRequest().body).utf8())
        assertTrue(!requestBody.has("size"))
        assertTrue(!requestBody.has("quality"))
        assertEquals(1, requestBody.getInt("n"))
    }

    @Test
    fun xaiImageRequestUsesCountAspectRatioAndResolutionFromSettings() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        val encoded = Base64.getEncoder().encodeToString(pngHeader)
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body(JSONObject().put("data", JSONArray()
                    .put(JSONObject().put("b64_json", encoded))
                    .put(JSONObject().put("b64_json", encoded))).toString())
                .build(),
        )

        val end = OpenAiAdapter.stream(
            provider = provider("openai"),
            model = ModelInfo(
                "grok-imagine-image-2.0",
                outputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            ),
            messages = listOf(ApiMessage("user", "请画两张 2:3 2k 的鹿")),
            params = GenParams(
                imageGeneration = ImageGenerationSettings(
                    count = 2,
                    aspectRatio = "2:3",
                    resolution = "2k",
                    quality = "high",
                ),
            ),
            tools = emptyList(),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        val requestBody = JSONObject(requireNotNull(server.takeRequest().body).utf8())
        assertEquals(2, requestBody.getInt("n"))
        assertEquals("2:3", requestBody.getString("aspect_ratio"))
        assertEquals("2k", requestBody.getString("resolution"))
        assertTrue(!requestBody.has("quality"))
        assertEquals("b64_json", requestBody.getString("response_format"))
        assertEquals("请画两张 2:3 2k 的鹿", requestBody.getString("prompt"))
        assertEquals(2, end.generatedImages.size)
    }

    @Test
    fun xaiImageRequestOmitsAutomaticAspectRatio() {
        enqueueGeneratedImage()

        OpenAiAdapter.stream(
            provider = provider("openai"),
            model = xaiImageModel(),
            messages = listOf(ApiMessage("user", "Draw a fawn")),
            params = GenParams(
                imageGeneration = ImageGenerationSettings(aspectRatio = "auto"),
            ),
            tools = emptyList(),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        val requestBody = JSONObject(requireNotNull(server.takeRequest().body).utf8())
        assertTrue(!requestBody.has("aspect_ratio"))
    }

    @Test
    fun xaiImageEditSendsSingleAttachmentAsJsonDataUri() {
        enqueueGeneratedImage()
        val source = ApiImage("image/jpeg", "single-base64")

        OpenAiAdapter.stream(
            provider = provider("openai"),
            model = xaiImageModel(),
            messages = listOf(ApiMessage("user", "Turn this into a sketch", images = listOf(source))),
            params = null,
            tools = emptyList(),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        val request = server.takeRequest()
        val requestBody = JSONObject(requireNotNull(request.body).utf8())
        assertTrue(request.requestLine.startsWith("POST /v1/images/edits "))
        val image = requestBody.getJSONObject("image")
        assertEquals("image_url", image.getString("type"))
        assertEquals("data:image/jpeg;base64,single-base64", image.getString("url"))
    }

    @Test
    fun xaiMultiImageEditSendsAttachmentsInOrder() {
        enqueueGeneratedImage()
        val sources = listOf(
            ApiImage("image/png", "first"),
            ApiImage("image/webp", "second"),
            ApiImage("image/jpeg", "third"),
        )

        OpenAiAdapter.stream(
            provider = provider("openai"),
            model = xaiImageModel(),
            messages = listOf(ApiMessage("user", "Combine these", images = sources)),
            params = GenParams(imageGeneration = ImageGenerationSettings(aspectRatio = "16:9")),
            tools = emptyList(),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        val requestBody = JSONObject(requireNotNull(server.takeRequest().body).utf8())
        val images = requestBody.getJSONArray("images")
        assertEquals(3, images.length())
        assertEquals("data:image/png;base64,first", images.getJSONObject(0).getString("url"))
        assertEquals("data:image/webp;base64,second", images.getJSONObject(1).getString("url"))
        assertEquals("data:image/jpeg;base64,third", images.getJSONObject(2).getString("url"))
        assertEquals("16:9", requestBody.getString("aspect_ratio"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun xaiImageEditRejectsMoreThanThreeAttachments() {
        OpenAiAdapter.stream(
            provider = provider("openai"),
            model = xaiImageModel(),
            messages = listOf(ApiMessage(
                "user",
                "Combine these",
                images = List(4) { ApiImage("image/jpeg", "image-$it") },
            )),
            params = null,
            tools = emptyList(),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )
    }

    @Test
    fun xaiImagineModelSupportsImageInputAndOutput() {
        val capabilities = ModelRegistry.infer("grok-imagine-image-2.0")

        assertTrue(Modality.IMAGE in capabilities.input)
        assertTrue(Modality.IMAGE in capabilities.output)
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
            provider = provider("google").copy(
                apiPath = "/custom/models/{model}:streamGenerateContent?alt=sse",
            ),
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
        assertTrue(
            server.takeRequest().requestLine
                .startsWith("POST /v1/custom/models/gemini-test:streamGenerateContent?alt=sse "),
        )
    }

    @Test
    fun googleMergesFunctionCallArgumentsAcrossStreamEvents() {
        enqueueSse(
            """{"candidates":[{"content":{"parts":[{"thoughtSignature":"sig","functionCall":{"name":"search_web","args":{"query":"fawn"}}}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"search_web","args":{"count":5}}}]}}]}""",
        )

        val end = GoogleAdapter.stream(
            provider = provider("google"),
            model = ModelInfo("gemini-test"),
            messages = listOf(ApiMessage("user", "hello")),
            params = null,
            tools = emptyList(),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        val call = end.toolCalls.single()
        val arguments = JSONObject(call.arguments)
        assertEquals("search_web", call.name)
        assertEquals("fawn", arguments.getString("query"))
        assertEquals(5, arguments.getInt("count"))
        assertEquals("sig", call.extra)
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
            provider = provider("claude").copy(apiPath = "/custom/messages"),
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
        assertTrue(server.takeRequest().requestLine.startsWith("POST /v1/custom/messages "))
    }

    private fun provider(type: String) = ApiProvider(
        type = type,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "test-key",
    )

    private fun xaiImageModel() = ModelInfo(
        "grok-imagine-image-2.0",
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        outputModalities = listOf(Modality.TEXT, Modality.IMAGE),
    )

    private fun enqueueGeneratedImage() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        ))
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body(JSONObject().put("data", JSONArray().put(
                    JSONObject().put("b64_json", encoded),
                )).toString())
                .build(),
        )
    }

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
