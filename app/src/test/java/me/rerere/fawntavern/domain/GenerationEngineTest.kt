package me.rerere.fawntavern.domain

import kotlinx.coroutines.runBlocking
import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.data.api.GenParams
import me.rerere.fawntavern.data.api.ApiRequestSnapshot
import me.rerere.fawntavern.data.api.ApiToolCall
import me.rerere.fawntavern.data.api.GeneratedImage
import me.rerere.fawntavern.data.api.StreamEnd
import me.rerere.fawntavern.data.api.ToolSpec
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.MsgAlt
import me.rerere.fawntavern.data.chat.MsgSearch
import me.rerere.fawntavern.data.chat.PersistedGeneratedImage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationEngineTest {
    @Test
    fun gatewayEventsAreMergedInEmissionOrder() = runBlocking {
        val gateway = GenerationGateway { _, onEvent ->
            onEvent(GenerationEvent.ReasoningDelta("think"))
            onEvent(GenerationEvent.ContentDelta("answer"))
            StreamEnd()
        }

        val result = GenerationEngine(gateway).run(
            apiMessages = listOf(ApiMessage("user", "question")),
            genMessage = ChatMessage(role = "assistant"),
            providerId = "provider",
            providerName = "Provider",
            modelId = "model",
            built = PromptBuilder.Built(),
            streaming = false,
            errorText = { it.message.orEmpty() },
            onUpdate = {},
        )

        assertEquals("think", result.reasoning)
        assertEquals("answer", result.content)
    }

    @Test
    fun failedRequestStillPersistsItsSnapshot() = runBlocking {
        val snapshot = ApiRequestSnapshot("https://example.com/fail", "{\"model\":\"test\"}")
        val client = testGateway { _, _, _, _, _, _, _ ->
            throw GenerationRequestException(snapshot, IllegalStateException("provider failed"))
        }

        val result = GenerationEngine(client).run(
            apiMessages = listOf(ApiMessage("user", "question")),
            genMessage = ChatMessage(role = "assistant", alts = listOf(MsgAlt())),
            providerId = "provider",
            providerName = "Provider",
            modelId = "model",
            built = PromptBuilder.Built(),
            streaming = false,
            errorText = { it.message.orEmpty() },
            onUpdate = {},
        )

        assertEquals("provider failed", result.content)
        assertEquals(listOf(snapshot), result.requestSnapshots)
        assertEquals(listOf(snapshot), result.alts.single().requestSnapshots)
    }

    @Test
    fun stopCancelsTheCurrentRound() = runBlocking {
        lateinit var controller: GenerationEngine
        val client = testGateway { _, _, _, _, _, isCancelled, _ ->
            controller.stop()
            if (isCancelled()) throw GenerationCancelled()
            StreamEnd()
        }
        controller = GenerationEngine(client)

        val result = controller.run(
            apiMessages = listOf(ApiMessage("user", "question")),
            genMessage = ChatMessage(role = "assistant"),
            providerId = "provider",
            providerName = "Provider",
            modelId = "model",
            built = PromptBuilder.Built(),
            streaming = false,
            errorText = { it.message.orEmpty() },
            onUpdate = {},
        )

        assertEquals("", result.content)
    }

    @Test
    fun toolResultIsAddedBeforeSecondModelRound() = runBlocking {
        val requests = mutableListOf<List<ApiMessage>>()
        val client = testGateway { _, _, messages, _, _, _, onDelta ->
            requests += messages
            if (requests.size == 1) {
                StreamEnd(
                    toolCalls = listOf(ApiToolCall("call-1", "search_web", "{\"query\":\"fawn\"}")),
                    requestSnapshot = ApiRequestSnapshot("https://example.com/round-1", "{\"round\":1}"),
                )
            } else {
                onDelta("final answer", "")
                StreamEnd(
                    promptTokens = 10,
                    completionTokens = 2,
                    requestSnapshot = ApiRequestSnapshot("https://example.com/round-2", "{\"round\":2}"),
                )
            }
        }
        val executor = object : GenerationToolExecutor {
            override fun describe(call: ApiToolCall): MsgSearch? = null

            override suspend fun execute(call: ApiToolCall): Pair<String, MsgSearch?> =
                "{\"items\":[\"result\"]}" to null
        }

        val result = GenerationEngine(client).run(
            apiMessages = listOf(ApiMessage("user", "question")),
            genMessage = ChatMessage(role = "assistant"),
            providerId = "provider",
            providerName = "Provider",
            modelId = "model",
            built = PromptBuilder.Built(),
            streaming = false,
            tools = listOf(ToolSpec("search_web", "search", "{\"type\":\"object\"}")),
            toolExecutor = executor,
            errorText = { it.message.orEmpty() },
            onUpdate = {},
        )

        assertEquals(2, requests.size)
        val toolRound = requests[1].last()
        assertEquals("assistant", toolRound.role)
        assertEquals("{\"items\":[\"result\"]}", toolRound.toolCalls.single().result)
        assertEquals("final answer", result.content)
        assertEquals(listOf("{\"round\":1}", "{\"round\":2}"), result.requestSnapshots.map { it.body })
        assertTrue(result.generationMs > 0)
    }

    @Test
    fun toolExecutionFailureIsReturnedAsValidJson() = runBlocking {
        val requests = mutableListOf<List<ApiMessage>>()
        val client = testGateway { _, _, messages, _, _, _, _ ->
            requests += messages
            if (requests.size == 1) {
                StreamEnd(toolCalls = listOf(ApiToolCall("call-1", "search_web", "{}")))
            } else {
                StreamEnd()
            }
        }
        val executor = object : GenerationToolExecutor {
            override fun describe(call: ApiToolCall): MsgSearch? = null

            override suspend fun execute(call: ApiToolCall): Pair<String, MsgSearch?> {
                throw IllegalStateException("bad \"query\"\nretry")
            }
        }

        GenerationEngine(client).run(
            apiMessages = listOf(ApiMessage("user", "question")),
            genMessage = ChatMessage(role = "assistant"),
            providerId = "provider",
            providerName = "Provider",
            modelId = "model",
            built = PromptBuilder.Built(),
            streaming = false,
            tools = listOf(ToolSpec("search_web", "search", "{\"type\":\"object\"}")),
            toolExecutor = executor,
            errorText = { it.message.orEmpty() },
            onUpdate = {},
        )

        val result = requests[1].last().toolCalls.single().result
        assertEquals("bad \"query\"\nretry", JSONObject(result).getString("error"))
    }

    @Test
    fun generatedImagesArePersistedIntoTheFinalMessage() = runBlocking {
        val image = GeneratedImage(byteArrayOf(1, 2, 3), "image/png")
        val client = testGateway { _, _, _, _, _, _, _ ->
            StreamEnd(generatedImages = listOf(image))
        }

        val result = GenerationEngine(client).run(
            apiMessages = listOf(ApiMessage("user", "draw")),
            genMessage = ChatMessage(
                role = "assistant",
                imageAspectRatio = "16:9",
                alts = listOf(MsgAlt()),
            ),
            providerId = "provider",
            providerName = "Provider",
            modelId = "image-model",
            built = PromptBuilder.Built(),
            streaming = false,
            persistGeneratedImage = { PersistedGeneratedImage("attachments/generated.png", "4:3") },
            errorText = { it.message.orEmpty() },
            onUpdate = {},
        )

        assertEquals(listOf("attachments/generated.png"), result.images)
        assertEquals(listOf("attachments/generated.png"), result.alts.single().images)
        assertEquals("16:9", result.alts.single().imageAspectRatio)
    }

    @Test
    fun automaticImageSizeUsesPersistedImageAspectRatio() = runBlocking {
        val image = GeneratedImage(byteArrayOf(1, 2, 3), "image/png")
        val client = testGateway { _, _, _, _, _, _, _ ->
            StreamEnd(generatedImages = listOf(image))
        }

        val result = GenerationEngine(client).run(
            apiMessages = listOf(ApiMessage("user", "draw")),
            genMessage = ChatMessage(
                role = "assistant",
                imageAspectRatio = "auto",
                alts = listOf(MsgAlt()),
            ),
            providerId = "provider",
            providerName = "Provider",
            modelId = "image-model",
            built = PromptBuilder.Built(),
            streaming = false,
            persistGeneratedImage = { PersistedGeneratedImage("attachments/generated.png", "3:2") },
            errorText = { it.message.orEmpty() },
            onUpdate = {},
        )

        assertEquals("3:2", result.imageAspectRatio)
        assertEquals("3:2", result.alts.single().imageAspectRatio)
    }
}

private fun interface TestStreamClient {
    suspend fun stream(
        providerId: String,
        modelId: String,
        messages: List<ApiMessage>,
        params: GenParams?,
        tools: List<ToolSpec>,
        isCancelled: () -> Boolean,
        onDelta: (content: String, reasoning: String) -> Unit,
    ): StreamEnd
}

private fun testGateway(client: TestStreamClient): GenerationGateway =
    GenerationGateway { request, onEvent ->
        client.stream(
            providerId = request.providerId,
            modelId = request.modelId,
            messages = request.messages,
            params = request.params,
            tools = request.tools,
            isCancelled = request.isCancelled,
            onDelta = { content, reasoning ->
                if (content.isNotEmpty()) onEvent(GenerationEvent.ContentDelta(content))
                if (reasoning.isNotEmpty()) onEvent(GenerationEvent.ReasoningDelta(reasoning))
            },
        )
    }
