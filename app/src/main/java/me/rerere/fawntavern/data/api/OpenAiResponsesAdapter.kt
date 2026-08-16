package me.rerere.fawntavern.data.api

import java.net.URI
import java.util.Base64
import me.rerere.fawntavern.data.api.SseClient.strOr
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI Responses API: POST {base}/responses. */
internal object OpenAiResponsesAdapter : ProviderAdapter {

    internal data class ParsedResponse(
        val content: String = "",
        val reasoning: String = "",
        val toolCalls: List<ApiToolCall> = emptyList(),
        val rawBlocks: String = "",
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val cachedTokens: Int = 0,
        val generatedImages: List<GeneratedImage> = emptyList(),
    )

    private data class MutableToolCall(
        var id: String = "",
        var name: String = "",
        var arguments: String = "",
    )

    override fun stream(
        provider: ApiProvider,
        model: ModelInfo,
        messages: List<ApiMessage>,
        params: GenParams?,
        tools: List<ToolSpec>,
        onDelta: (content: String, reasoning: String) -> Unit,
        stopped: () -> Unit,
        onCall: (okhttp3.Call) -> Unit,
    ): StreamEnd {
        val callsByIndex = sortedMapOf<Int, MutableToolCall>()
        var promptTokens = 0
        var completionTokens = 0
        var cachedTokens = 0
        var rawBlocks = ""
        var generatedImages = emptyList<GeneratedImage>()
        var receivedContentDelta = false
        var receivedReasoningDelta = false

        SseClient.post(
            url = endpoint(provider),
            headers = model.applyHeaders(mapOf("Authorization" to "Bearer ${provider.apiKey}")),
            body = buildRequestBody(provider, model, messages, params, tools, stream = true),
            stopped = stopped,
            onCall = onCall,
        ) { data ->
            if (data == "[DONE]") return@post
            val event = JSONObject(data)
            throwIfErrorEvent(event)
            when (event.strOr("type")) {
                "response.output_text.delta" -> event.strOr("delta").takeIf { it.isNotEmpty() }?.let {
                    receivedContentDelta = true
                    onDelta(it, "")
                }
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                    event.strOr("delta").takeIf { it.isNotEmpty() }?.let {
                        receivedReasoningDelta = true
                        onDelta("", it)
                    }
                "response.output_item.added", "response.output_item.done" -> {
                    val item = event.optJSONObject("item") ?: return@post
                    if (item.strOr("type") == "function_call") {
                        val index = event.optInt("output_index", callsByIndex.size)
                        val call = callsByIndex.getOrPut(index) { MutableToolCall() }
                        call.id = item.strOr("call_id").ifBlank { item.strOr("id") }.ifBlank { call.id }
                        call.name = item.strOr("name").ifBlank { call.name }
                        if (event.strOr("type") == "response.output_item.done") {
                            item.strOr("arguments").takeIf { it.isNotEmpty() }?.let { call.arguments = it }
                        }
                    }
                }
                "response.function_call_arguments.delta" -> {
                    val index = event.optInt("output_index", callsByIndex.size)
                    val call = callsByIndex.getOrPut(index) { MutableToolCall() }
                    call.arguments += event.strOr("delta")
                }
                "response.function_call_arguments.done" -> {
                    val index = event.optInt("output_index", callsByIndex.size)
                    val call = callsByIndex.getOrPut(index) { MutableToolCall() }
                    call.id = event.strOr("call_id")
                        .ifBlank { event.strOr("item_id") }
                        .ifBlank { call.id }
                    event.strOr("name").takeIf { it.isNotEmpty() }?.let { call.name = it }
                    event.strOr("arguments").takeIf { it.isNotEmpty() }?.let { call.arguments = it }
                }
                "response.completed" -> {
                    val parsed = parseCompletedResponse(event.optJSONObject("response") ?: event)
                    if (!receivedContentDelta && parsed.content.isNotEmpty()) onDelta(parsed.content, "")
                    if (!receivedReasoningDelta && parsed.reasoning.isNotEmpty()) onDelta("", parsed.reasoning)
                    parsed.toolCalls.forEachIndexed { index, parsedCall ->
                        val existingIndex = callsByIndex.entries
                            .firstOrNull { it.value.id == parsedCall.id && parsedCall.id.isNotBlank() }
                            ?.key ?: index
                        val call = callsByIndex.getOrPut(existingIndex) { MutableToolCall() }
                        call.id = parsedCall.id.ifBlank { call.id }
                        call.name = parsedCall.name.ifBlank { call.name }
                        call.arguments = parsedCall.arguments.ifBlank { call.arguments }
                    }
                    rawBlocks = parsed.rawBlocks
                    promptTokens = parsed.promptTokens
                    completionTokens = parsed.completionTokens
                    cachedTokens = parsed.cachedTokens
                    generatedImages = parsed.generatedImages
                }
            }
        }

        return StreamEnd(
            toolCalls = callsByIndex.values.mapIndexedNotNull { index, call ->
                call.name.takeIf { it.isNotBlank() }?.let {
                    ApiToolCall(
                        id = call.id.ifBlank { "call_$index" },
                        name = it,
                        arguments = call.arguments.ifBlank { "{}" },
                    )
                }
            },
            rawBlocks = rawBlocks,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
            generatedImages = generatedImages,
        )
    }

    internal fun endpoint(provider: ApiProvider): String {
        val base = provider.baseUrl.trimEnd('/')
        if (provider.apiPath.isNotBlank()) {
            return provider.apiEndpoint("/responses")
        }
        if (base.endsWith("/responses")) return base
        val uri = runCatching { URI(base) }.getOrNull()
        if (uri?.host?.lowercase()?.endsWith("dashscope.aliyuncs.com") == true &&
            uri.path.orEmpty().trimEnd('/') != DASHSCOPE_RESPONSES_BASE
        ) {
            return "${uri.scheme}://${uri.rawAuthority}$DASHSCOPE_RESPONSES_BASE/responses"
        }
        return "$base/responses"
    }

    internal fun buildRequestBody(
        provider: ApiProvider,
        model: ModelInfo,
        messages: List<ApiMessage>,
        params: GenParams?,
        tools: List<ToolSpec>,
        stream: Boolean,
    ): JSONObject = JSONObject().apply {
        put("model", model.id)
        put("stream", stream)
        put("store", false)
        params?.temperature?.let { put("temperature", it.toDouble()) }
        params?.topP?.let { put("top_p", it.toDouble()) }
        params?.maxTokens?.let { put("max_output_tokens", it) }

        messages.filter { it.role == "system" }
            .map { it.content }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
            ?.let { put("instructions", it) }
        put("input", encodeInput(messages))

        if (ModelAbility.REASONING in model.abilities) {
            val supportsReasoningMetadata = runCatching { URI(provider.baseUrl).host.lowercase() }
                .getOrNull() != "ark.cn-beijing.volces.com"
            put("reasoning", JSONObject().apply {
                if (supportsReasoningMetadata) put("summary", "auto")
                params?.reasoning?.takeUnless { it == ReasoningLevel.AUTO }
                    ?.let { put("effort", it.effort) }
            })
            if (supportsReasoningMetadata) {
                put("include", JSONArray().put("reasoning.encrypted_content"))
            }
        }

        val encodedTools = JSONArray()
        tools.forEach { tool ->
            encodedTools.put(JSONObject()
                .put("type", "function")
                .put("name", tool.name)
                .put("description", tool.description)
                .put("parameters", JSONObject(tool.parametersSchema)))
        }
        if (BuiltInTool.SEARCH in model.tools) {
            encodedTools.put(JSONObject().put("type", "web_search"))
        }
        if (encodedTools.length() > 0) put("tools", encodedTools)
        applyCustomBodies(model)
    }

    internal fun encodeInput(messages: List<ApiMessage>): JSONArray = JSONArray().apply {
        messages.filterNot { it.role == "system" }.forEach { message ->
            if (message.rawBlocks.isNotBlank()) {
                runCatching { JSONArray(message.rawBlocks) }.getOrNull()?.let { raw ->
                    for (index in 0 until raw.length()) raw.optJSONObject(index)?.let { put(it) }
                }
            }
            encodeMessageContent(message).forEach { put(it) }
            message.toolCalls.forEach { call ->
                put(JSONObject()
                    .put("type", "function_call")
                    .put("call_id", call.id)
                    .put("name", call.name)
                    .put("arguments", call.arguments.ifBlank { "{}" }))
                put(JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", call.id)
                    .put("output", call.result))
            }
        }
    }

    private fun encodeMessageContent(message: ApiMessage): List<JSONObject> {
        if (message.content.isBlank() && message.images.isEmpty()) return emptyList()
        if (message.images.isEmpty()) {
            return listOf(JSONObject().put("role", message.role).put("content", message.content))
        }
        val out = mutableListOf<JSONObject>()
        if (message.role == "assistant" && message.content.isNotBlank()) {
            out += JSONObject().put("role", "assistant").put("content", message.content)
        }
        val parts = JSONArray()
        if (message.role != "assistant" && message.content.isNotBlank()) {
            parts.put(JSONObject().put("type", "input_text").put("text", message.content))
        }
        message.images.forEach { image ->
            parts.put(JSONObject()
                .put("type", "input_image")
                .put("image_url", "data:${image.mimeType};base64,${image.base64}"))
        }
        if (parts.length() > 0) out += JSONObject().put("role", "user").put("content", parts)
        return out
    }

    internal fun parseCompletedResponse(response: JSONObject): ParsedResponse {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ApiToolCall>()
        val rawBlocks = JSONArray()
        val images = mutableListOf<GeneratedImage>()
        val output = response.optJSONArray("output")
        for (index in 0 until (output?.length() ?: 0)) {
            val item = output?.optJSONObject(index) ?: continue
            when (item.strOr("type")) {
                "message" -> {
                    val parts = item.optJSONArray("content")
                    for (partIndex in 0 until (parts?.length() ?: 0)) {
                        val part = parts?.optJSONObject(partIndex) ?: continue
                        if (part.strOr("type") in setOf("output_text", "text")) {
                            content.append(part.strOr("text").ifBlank { part.strOr("content") })
                        }
                    }
                }
                "output_text" -> content.append(item.strOr("text").ifBlank { item.strOr("content") })
                "reasoning" -> {
                    rawBlocks.put(JSONObject(item.toString()))
                    val summary = item.optJSONArray("summary")
                    for (partIndex in 0 until (summary?.length() ?: 0)) {
                        reasoning.append(summary?.optJSONObject(partIndex)?.strOr("text").orEmpty())
                    }
                }
                "function_call" -> calls += ApiToolCall(
                    id = item.strOr("call_id").ifBlank { item.strOr("id") }.ifBlank { "call_$index" },
                    name = item.strOr("name"),
                    arguments = item.strOr("arguments").ifBlank { "{}" },
                )
                "image_generation_call" -> item.strOr("result").takeIf { it.isNotBlank() }?.let { encoded ->
                    decodeGeneratedImage(encoded, item.strOr("output_format"))?.let(images::add)
                }
            }
        }
        if (content.isEmpty()) content.append(response.strOr("output_text"))
        val usage = response.optJSONObject("usage")
        return ParsedResponse(
            content = content.toString(),
            reasoning = reasoning.toString(),
            toolCalls = calls.filter { it.name.isNotBlank() },
            rawBlocks = rawBlocks.takeIf { it.length() > 0 }?.toString().orEmpty(),
            promptTokens = usage?.optInt("input_tokens", 0) ?: 0,
            completionTokens = usage?.optInt("output_tokens", 0) ?: 0,
            cachedTokens = usage?.optJSONObject("input_tokens_details")
                ?.optInt("cached_tokens", 0) ?: 0,
            generatedImages = images,
        )
    }

    private fun throwIfErrorEvent(event: JSONObject) {
        val type = event.strOr("type")
        if (type !in setOf("error", "response.failed", "response.incomplete")) return
        val response = event.optJSONObject("response")
        val error = event.optJSONObject("error") ?: response?.optJSONObject("error")
        val incomplete = response?.optJSONObject("incomplete_details")?.strOr("reason").orEmpty()
        val message = error?.strOr("message")
            .orEmpty()
            .ifBlank { event.strOr("message") }
            .ifBlank { incomplete }
            .ifBlank { type }
        throw IllegalStateException("Provider error: $message")
    }

    private fun decodeGeneratedImage(encoded: String, outputFormat: String): GeneratedImage? {
        if (encoded.length > MAX_IMAGE_BASE64_CHARS) return null
        val bytes = runCatching { Base64.getDecoder().decode(encoded.substringAfter(',', encoded)) }
            .getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val mime = when (outputFormat.lowercase()) {
            "jpeg", "jpg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        return GeneratedImage(bytes, mime)
    }

    private const val DASHSCOPE_RESPONSES_BASE = "/api/v2/apps/protocols/compatible-mode/v1"
    private const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
    private const val MAX_IMAGE_BASE64_CHARS = ((MAX_IMAGE_BYTES + 2) / 3) * 4
}
