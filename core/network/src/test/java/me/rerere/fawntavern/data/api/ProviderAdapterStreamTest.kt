package me.rerere.fawntavern.data.api

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("model", JSONObject(requireNotNull(end.requestSnapshot).body).getString("model"))
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
    fun modelChatRouteAndProviderChatPathAreIndependent() {
        enqueueSse("[DONE]")

        OpenAiAdapter.stream(
            provider = provider("openai").copy(
                useResponseApi = true,
                chatApiPath = "/custom/chat",
                responsesApiPath = "/custom/responses",
            ),
            model = ModelInfo(
                id = "chat-model",
                chatGenerationRoute = ChatGenerationRoute.CHAT_COMPLETIONS,
            ),
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
        assertEquals("system", JSONObject(requireNotNull(end.requestSnapshot).body).getString("instructions"))
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
        assertEquals("2K", JSONObject(requireNotNull(end.requestSnapshot).body)
            .getJSONObject("generationConfig").getJSONObject("imageConfig").getString("imageSize"))
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
            params = GenParams(),
            tools = listOf(ToolSpec("search_web", "Search", "{\"type\":\"object\"}")),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        val body = JSONObject(requireNotNull(server.takeRequest().body).utf8())
        val encodedTools = body.getJSONArray("tools")
        assertTrue(encodedTools.getJSONObject(0).has("googleSearch"))
        assertTrue(encodedTools.getJSONObject(1).has("functionDeclarations"))
        assertFalse(body.has("toolConfig"))
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
            provider = provider("openai").copy(useResponseApi = true),
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
        assertEquals(
            "System context:\nignore this\n\nUser:\ndraw a fawn",
            requestBody.getString("prompt"),
        )
        assertTrue(end.generatedImages.single().bytes.contentEquals(pngHeader))
        assertEquals("image/png", end.generatedImages.single().mimeType)
    }

    @Test
    fun openAiResponsesImageToolReceivesNativeConversationContext() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val encoded = Base64.getEncoder().encodeToString(png)
        enqueueSse(
            JSONObject().put("type", "response.completed").put("response", JSONObject()
                .put("usage", JSONObject().put("input_tokens", 20).put("output_tokens", 5))
                .put("output", JSONArray().put(JSONObject()
                    .put("type", "image_generation_call")
                    .put("output_format", "png")
                    .put("result", encoded)))).toString(),
        )
        val model = ModelInfo(
            id = "gpt-5.6",
            outputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            type = ModelType.IMAGE,
            imageGenerationRoute = ImageGenerationRoute.RESPONSES_TOOL,
        )

        val end = OpenAiAdapter.stream(
            provider = provider("openai").copy(
                apiPath = "/legacy/chat/completions",
                responsesApiPath = "/custom/responses",
            ),
            model = model,
            messages = listOf(
                ApiMessage("system", "A fawn character with a blue scarf"),
                ApiMessage("assistant", "The fawn enters the tavern"),
                ApiMessage("user", "Draw this scene at night"),
            ),
            params = GenParams(imageGeneration = ImageGenerationSettings(
                aspectRatio = "1:1",
                quality = "high",
                includeContext = true,
            )),
            tools = emptyList(),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        val request = server.takeRequest()
        val body = JSONObject(requireNotNull(request.body).utf8())
        assertTrue(request.requestLine.startsWith("POST /v1/custom/responses "))
        assertEquals("gpt-5.6", body.getString("model"))
        assertEquals("A fawn character with a blue scarf", body.getString("instructions"))
        val input = body.getJSONArray("input")
        assertEquals("assistant", input.getJSONObject(0).getString("role"))
        assertEquals("The fawn enters the tavern", input.getJSONObject(0).getString("content"))
        assertEquals("user", input.getJSONObject(1).getString("role"))
        assertEquals("Draw this scene at night", input.getJSONObject(1).getString("content"))
        val imageTool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("image_generation", imageTool.getString("type"))
        assertEquals("generate", imageTool.getString("action"))
        assertFalse(imageTool.has("size"))
        assertFalse(imageTool.has("quality"))
        assertTrue(end.generatedImages.single().bytes.contentEquals(png))
    }

    @Test
    fun openAiResponsesImageToolUsesOfficialOutputOptions() {
        val model = ModelInfo(
            id = "gpt-5.6",
            outputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            type = ModelType.IMAGE,
            imageGenerationRoute = ImageGenerationRoute.RESPONSES_TOOL,
        )

        val body = OpenAiResponsesAdapter.buildRequestBody(
            provider = ApiProvider(type = "openai", baseUrl = "https://api.openai.com/v1"),
            model = model,
            messages = listOf(ApiMessage("user", "Draw a square image")),
            params = GenParams(imageGeneration = ImageGenerationSettings(
                aspectRatio = "1:1",
                quality = "high",
            )),
            tools = emptyList(),
            stream = true,
        )

        val imageTool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("1024x1024", imageTool.getString("size"))
        assertEquals("high", imageTool.getString("quality"))
    }

    @Test
    fun xaiResponsesImageToolUsesNativeContextWithoutOpenAiOnlyOptions() {
        val provider = ApiProvider(
            type = "openai",
            baseUrl = "https://api.x.ai/v1",
        )
        val model = ModelInfo(
            id = "grok-4.6",
            outputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            type = ModelType.IMAGE,
            imageGenerationRoute = ImageGenerationRoute.RESPONSES_TOOL,
        )

        val body = OpenAiResponsesAdapter.buildRequestBody(
            provider = provider,
            model = model,
            messages = listOf(
                ApiMessage("system", "Keep the character appearance consistent"),
                ApiMessage("assistant", "The character enters a neon city"),
                ApiMessage("user", "Draw the next scene"),
            ),
            params = GenParams(imageGeneration = ImageGenerationSettings(
                aspectRatio = "16:9",
                quality = "high",
            )),
            tools = emptyList(),
            stream = true,
        )

        assertEquals("Keep the character appearance consistent", body.getString("instructions"))
        assertEquals(2, body.getJSONArray("input").length())
        val imageTool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("image_generation", imageTool.getString("type"))
        assertEquals("generate", imageTool.getString("action"))
        assertFalse(imageTool.has("size"))
        assertFalse(imageTool.has("quality"))
    }

    @Test
    fun openAiImageRequestMapsSettingsToOfficialImageApiFields() {
        enqueueGeneratedImage()

        val end = OpenAiAdapter.stream(
            provider = ApiProvider(
                type = "openai",
                baseUrl = server.url("/v1").toString(),
                imageGenerationApiPath = "/custom/images/generations",
            ),
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

        val request = server.takeRequest()
        val requestBody = JSONObject(requireNotNull(request.body).utf8())
        assertTrue(request.requestLine.startsWith("POST /v1/custom/images/generations "))
        assertEquals(2, requestBody.getInt("n"))
        assertEquals("1024x1536", requestBody.getString("size"))
        assertEquals("high", requestBody.getString("quality"))
        assertTrue(!requestBody.has("aspect_ratio"))
        assertTrue(!requestBody.has("resolution"))
        assertEquals("high", JSONObject(requireNotNull(end.requestSnapshot).body).getString("quality"))
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
            provider = provider("openai").copy(imageEditApiPath = "/custom/images/edits"),
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
        assertTrue(request.requestLine.startsWith("POST /v1/custom/images/edits "))
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
    fun imagePromptCanIncludeOrExcludeConversationContext() {
        val messages = listOf(
            ApiMessage("system", "A fawn character with a blue scarf"),
            ApiMessage("assistant", "The fawn enters the tavern"),
            ApiMessage("user", "Draw this scene at night"),
        )

        val contextual = imageGenerationPrompt(messages, includeContext = true)
        val currentOnly = imageGenerationPrompt(messages, includeContext = false)

        assertTrue(contextual.contains("System context:\nA fawn character with a blue scarf"))
        assertTrue(contextual.contains("Assistant:\nThe fawn enters the tavern"))
        assertTrue(contextual.endsWith("User:\nDraw this scene at night"))
        assertEquals("Draw this scene at night", currentOnly)
    }

    @Test
    fun gradioImageAdapterUsesNamedApiAndDownloadsCompletedImage() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        server.enqueue(MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body("""{"event_id":"event-1"}""")
            .build())
        server.enqueue(MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body("event: complete\ndata: [{\"url\":\"/generated.png\"},123]\n\n")
            .build())
        server.enqueue(MockResponse.Builder()
            .addHeader("Content-Type", "image/png")
            .body(okio.Buffer().write(png))
            .build())

        val provider = ApiProvider(
            type = "gradio",
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiPath = "/generate_image",
            apiKey = "hf-test",
        )
        val model = ModelInfo(
            id = "z-image-turbo",
            outputModalities = listOf(Modality.IMAGE),
            type = ModelType.IMAGE,
        )
        val end = GradioImageAdapter.stream(
            provider = provider,
            model = model,
            messages = listOf(ApiMessage("user", "draw a fawn")),
            params = GenParams(
                imageGeneration = ImageGenerationSettings(
                    aspectRatio = "1:1",
                    resolution = "1k",
                    steps = 12,
                    seed = 42,
                ),
            ),
            tools = emptyList(),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        val submit = server.takeRequest()
        val events = server.takeRequest()
        val download = server.takeRequest()
        assertTrue(submit.requestLine.startsWith("POST /gradio_api/call/generate_image "))
        assertEquals("Bearer hf-test", submit.headers["Authorization"])
        val data = JSONObject(requireNotNull(submit.body).utf8()).getJSONArray("data")
        assertEquals("draw a fawn", data.getString(0))
        assertEquals(1024, data.getInt(1))
        assertEquals(1024, data.getInt(2))
        assertEquals(12, data.getInt(3))
        assertEquals(42, data.getInt(4))
        assertFalse(data.getBoolean(5))
        assertTrue(events.requestLine.startsWith("GET /gradio_api/call/generate_image/event-1 "))
        assertTrue(download.requestLine.startsWith("GET /generated.png "))
        assertTrue(end.generatedImages.single().bytes.contentEquals(png))
    }

    @Test
    fun gradioOfficialProfileUsesQueueApiAndParsesGalleryImage() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        server.enqueue(MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body("""{"dependencies":[{"id":2,"api_name":"generate","targets":[[17,"click"]]}]}""")
            .build())
        server.enqueue(MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body("""{"event_id":"official-event"}""")
            .build())
        server.enqueue(MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body("data: {\"msg\":\"process_completed\",\"output\":{\"data\":[[{\"image\":{\"url\":\"/official.png\"},\"caption\":null}],\"42\",42]},\"success\":true}\n\n")
            .build())
        server.enqueue(MockResponse.Builder()
            .addHeader("Content-Type", "image/png")
            .body(okio.Buffer().write(png))
            .build())

        val provider = ApiProvider(
            type = "gradio",
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "hf-test",
            gradioImageProfile = GradioImageProfile.Z_IMAGE_OFFICIAL,
        )
        val model = ModelInfo(
            id = "tongyi-mai/z-image-turbo",
            outputModalities = listOf(Modality.IMAGE),
            type = ModelType.IMAGE,
        )
        val end = GradioImageAdapter.stream(
            provider = provider,
            model = model,
            messages = listOf(ApiMessage("user", "draw an official fawn")),
            params = GenParams(
                imageGeneration = ImageGenerationSettings(
                    aspectRatio = "16:9",
                    resolution = "4k",
                    steps = 9,
                    seed = 42,
                ),
            ),
            tools = emptyList(),
            onDelta = { _, _ -> },
            stopped = {},
            onCall = {},
        )

        val config = server.takeRequest()
        val submit = server.takeRequest()
        val events = server.takeRequest()
        val download = server.takeRequest()
        assertTrue(config.requestLine.startsWith("GET /config "))
        assertEquals("Bearer hf-test", config.headers["Authorization"])
        assertTrue(submit.requestLine.startsWith("POST /gradio_api/queue/join "))
        assertEquals("Bearer hf-test", submit.headers["Authorization"])
        val joinBody = JSONObject(requireNotNull(submit.body).utf8())
        val data = joinBody.getJSONArray("data")
        assertEquals("draw an official fawn", data.getString(0))
        assertEquals("2048x1152 ( 16:9 )", data.getString(1))
        assertEquals(42, data.getInt(2))
        assertEquals(8, data.getInt(3))
        assertEquals(3.0, data.getDouble(4), 0.0)
        assertFalse(data.getBoolean(5))
        assertEquals(0, data.getJSONArray(6).length())
        assertEquals(2, joinBody.getInt("fn_index"))
        assertEquals(17, joinBody.getInt("trigger_id"))
        assertTrue(joinBody.getString("session_hash").isNotBlank())
        assertTrue(events.requestLine.startsWith("GET /gradio_api/queue/data?session_hash="))
        assertEquals("Bearer hf-test", events.headers["Authorization"])
        assertTrue(download.requestLine.startsWith("GET /official.png "))
        assertTrue(end.generatedImages.single().bytes.contentEquals(png))
    }

    @Test
    fun gradioOfficialProfileSurfacesQueueError() {
        server.enqueue(MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body("""{"dependencies":[{"id":2,"api_name":"generate","targets":[[17,"click"]]}]}""")
            .build())
        server.enqueue(MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body("""{"event_id":"quota-event"}""")
            .build())
        server.enqueue(MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body("data: {\"msg\":\"process_completed\",\"output\":{\"error\":\"60s requested vs. 0s left\",\"title\":\"ZeroGPU quota exceeded\"},\"success\":false,\"title\":\"ZeroGPU quota exceeded\"}\n\n")
            .build())
        val provider = ApiProvider(
            type = "gradio",
            baseUrl = server.url("/").toString().trimEnd('/'),
            gradioImageProfile = GradioImageProfile.Z_IMAGE_OFFICIAL,
        )
        val model = ModelInfo(
            id = "tongyi-mai/z-image-turbo",
            outputModalities = listOf(Modality.IMAGE),
            type = ModelType.IMAGE,
        )

        val error = runCatching {
            GradioImageAdapter.stream(
                provider, model,
                listOf(ApiMessage("user", "draw")),
                GenParams(imageGeneration = ImageGenerationSettings(seed = 42)),
                emptyList(), { _, _ -> }, {}, {},
            )
        }.exceptionOrNull()

        assertTrue(error is ApiRequestException)
        assertTrue(error?.message.orEmpty().contains("ZeroGPU quota exceeded"))
        assertTrue(error?.message.orEmpty().contains("60s requested vs. 0s left"))
    }

    @Test
    fun gradioImageAdapterSubmitsRequestedCountSequentially() {
        repeat(2) { index ->
            server.enqueue(MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("""{"event_id":"event-$index"}""")
                .build())
            val dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(byteArrayOf(index.toByte()))
            server.enqueue(MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body("event: complete\ndata: [\"$dataUrl\",$index]\n\n")
                .build())
        }
        val provider = ApiProvider(
            type = "gradio",
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiPath = "/generate_image",
        )
        val model = ModelInfo(
            id = "z-image-turbo",
            outputModalities = listOf(Modality.IMAGE),
            type = ModelType.IMAGE,
        )

        val end = GradioImageAdapter.stream(
            provider, model,
            listOf(ApiMessage("user", "draw")),
            GenParams(imageGeneration = ImageGenerationSettings(count = 2, seed = 7)),
            emptyList(), { _, _ -> }, {}, {},
        )

        assertEquals(2, end.generatedImages.size)
        val first = JSONObject(requireNotNull(server.takeRequest().body).utf8()).getJSONArray("data")
        server.takeRequest()
        val second = JSONObject(requireNotNull(server.takeRequest().body).utf8()).getJSONArray("data")
        assertEquals(7, first.getInt(4))
        assertEquals(8, second.getInt(4))
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
        assertEquals("claude-test", JSONObject(requireNotNull(end.requestSnapshot).body).getString("model"))
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
