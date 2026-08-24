package me.rerere.fawntavern.data.api

import java.util.Base64
import me.rerere.fawntavern.data.api.SseClient.strOr
import org.json.JSONArray
import org.json.JSONObject

/** Google Gemini 协议: POST {base}/models/{m}:streamGenerateContent?alt=sse */
internal object GoogleAdapter : ProviderAdapter {

    override fun stream(
        provider: ApiProvider, model: ModelInfo,
        messages: List<ApiMessage>, params: GenParams?, tools: List<ToolSpec>,
        onDelta: (String, String) -> Unit,
        stopped: () -> Unit, onCall: (okhttp3.Call) -> Unit,
    ): StreamEnd {
        // 开头连续 system → systemInstruction；对话中间的 system（深度注入等）降级为 user
        val (system, rest) = splitLeadingSystem(messages)
        val merged = mergeConsecutive(rest.map { if (it.role == "system") it.copy(role = "user") else it })
        val body = JSONObject().apply {
            if (system.isNotBlank()) {
                put("systemInstruction", JSONObject().put("parts",
                    JSONArray().put(JSONObject().put("text", system))))
            }
            val cfg = JSONObject()
            params?.let { p ->
                p.temperature?.let { cfg.put("temperature", it.toDouble()) }
                p.topP?.let { cfg.put("topP", it.toDouble()) }
                p.topK?.takeIf { it > 0 }?.let { cfg.put("topK", it) }
                p.maxTokens?.let { cfg.put("maxOutputTokens", it) }
                p.frequencyPenalty?.let { cfg.put("frequencyPenalty", it.toDouble()) }
                p.presencePenalty?.let { cfg.put("presencePenalty", it.toDouble()) }
                p.seed?.let { cfg.put("seed", it) }
            }
            val level = params?.reasoning ?: ReasoningLevel.AUTO
            if (level != ReasoningLevel.AUTO) {
                cfg.put("thinkingConfig", JSONObject().apply {
                    // 不打开 includeThoughts 就收不到思考内容（Gemini 默认不回传）
                    put("includeThoughts", level.isEnabled)
                    if (model.id.contains("gemini-3", ignoreCase = true)) {
                        // Gemini 3 起用 thinkingLevel 枚举取代 token 预算；
                        // Pro 系不接受 minimal（思考关不掉），关闭档降级为 low
                        val isPro = model.id.contains("pro", ignoreCase = true)
                        put("thinkingLevel", when (level) {
                            ReasoningLevel.OFF -> if (isPro) "low" else "minimal"
                            ReasoningLevel.LOW -> "low"
                            ReasoningLevel.MEDIUM -> "medium"
                            else -> "high"
                        })
                    } else if (level.isEnabled) {
                        put("thinkingBudget", level.budgetTokens)
                    } else if (!model.id.contains("2.5-pro", ignoreCase = true)) {
                        // 2.5 Pro 根本不允许关掉思考（budget 0 会被拒），只能不发预算、单纯不回传思考内容
                        put("thinkingBudget", 0)
                    }
                })
            }
            if (isImageGenerationModel(model)) {
                cfg.put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
                val imageConfig = JSONObject()
                params?.imageGeneration?.let { settings ->
                    geminiImageAspectRatio(settings.aspectRatio)?.let { imageConfig.put("aspectRatio", it) }
                    geminiImageSize(settings.resolution)?.let { imageConfig.put("imageSize", it) }
                }
                if (imageConfig.length() > 0) cfg.put("imageConfig", imageConfig)
            }
            if (cfg.length() > 0) put("generationConfig", cfg)
            val encodedTools = JSONArray()
            if (BuiltInTool.SEARCH in model.tools) encodedTools.put(JSONObject().put("googleSearch", JSONObject()))
            if (BuiltInTool.URL_CONTEXT in model.tools) encodedTools.put(JSONObject().put("urlContext", JSONObject()))
            if (tools.isNotEmpty()) {
                encodedTools.put(JSONObject().put("functionDeclarations", JSONArray().apply {
                    tools.forEach { t ->
                        put(JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", JSONObject(t.parametersSchema)))
                    }
                }))
            }
            if (encodedTools.length() > 0) put("tools", encodedTools)
            if (tools.isNotEmpty() && params?.toolChoice != ToolChoice.AUTO) {
                put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().apply {
                    put("mode", when (params?.toolChoice) {
                        ToolChoice.REQUIRED -> "ANY"
                        ToolChoice.NONE -> "NONE"
                        else -> "AUTO"
                    })
                }))
            }
            put("contents", JSONArray().apply {
                merged.forEach { m -> encodeContents(m).forEach { put(it) } }
            })
            applyCustomBodies(model)
        }
        val toolCalls = linkedMapOf<String, ApiToolCall>()
        val generatedImages = mutableListOf<GeneratedImage>()
        var promptTokens = 0
        var completionTokens = 0
        var cachedTokens = 0
        val endpoint = provider.apiEndpoint(
            "/models/{model}:streamGenerateContent?alt=sse",
            model.id,
        )
        val snapshot = requestSnapshot(endpoint, body)
        captureRequestFailure(snapshot, stopped) {
            SseClient.post(
            url = endpoint,
            // 密钥走 x-goog-api-key 请求头，不再拼进 URL
            headers = model.applyHeaders(mapOf("x-goog-api-key" to provider.apiKey)),
            body = body,
            stopped = stopped,
            onCall = onCall,
            ) { data ->
            val obj = JSONObject(data)
            obj.optJSONObject("usageMetadata")?.let { usage ->
                promptTokens = usage.optInt("promptTokenCount", promptTokens)
                completionTokens = usage.optInt("candidatesTokenCount", completionTokens) +
                    usage.optInt("thoughtsTokenCount", 0)
                cachedTokens = usage.optInt("cachedContentTokenCount", cachedTokens)
            }
            val parts = obj.optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return@post
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                // 函数调用整块到达（不分片）；thoughtSignature 挂在同一 part 上，回传必须回显
                part.optJSONObject("functionCall")?.let { fc ->
                    val name = fc.optString("name")
                    if (name.isNotBlank()) {
                        val key = part.strOr("id").ifBlank { "$name#$i" }
                        val previous = toolCalls[key]
                        val args = fc.optJSONObject("args") ?: JSONObject()
                        val mergedArgs = runCatching {
                            JSONObject(previous?.arguments ?: "{}").apply {
                                val keys = args.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    put(k, args.opt(k))
                                }
                            }
                        }.getOrDefault(args)
                        toolCalls[key] = ApiToolCall(
                            id = previous?.id ?: "fc_${toolCalls.size}",
                            name = name,
                            arguments = mergedArgs.toString(),
                            extra = part.strOr("thoughtSignature").ifBlank { previous?.extra.orEmpty() },
                        )
                    }
                }
                val text = part.strOr("text")
                part.optJSONObject("inlineData")?.let { decodeGeneratedImage(it)?.let(generatedImages::add) }
                part.optJSONObject("inline_data")?.let { decodeGeneratedImage(it)?.let(generatedImages::add) }
                if (text.isEmpty()) continue
                // 思考内容与正文混在同一个 parts 数组里，只靠 thought=true 区分（不分流会当正文输出）
                if (part.optBoolean("thought")) onDelta("", text) else onDelta(text, "")
            }
            }
        }
        return StreamEnd(
            toolCalls = toolCalls.values.toList(),
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
            generatedImages = generatedImages,
            requestSnapshot = snapshot,
        )
    }

    internal fun isImageGenerationModel(model: ModelInfo): Boolean =
        Modality.IMAGE in model.outputModalities

    internal fun geminiImageAspectRatio(value: String): String? =
        value.takeUnless { it.equals("auto", ignoreCase = true) }
            ?.takeIf { it in GEMINI_IMAGE_ASPECT_RATIOS }

    internal fun geminiImageSize(value: String): String? = when (value.lowercase()) {
        "0.5k", "512px" -> "0.5K"
        "1k" -> "1K"
        "2k" -> "2K"
        "4k" -> "4K"
        else -> null
    }

    private fun decodeGeneratedImage(inline: JSONObject): GeneratedImage? {
        val encoded = inline.strOr("data")
        if (encoded.isBlank()) return null
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_GENERATED_IMAGE_BYTES) return null
        val mime = inline.strOr("mimeType").ifBlank { inline.strOr("mime_type") }
        return GeneratedImage(bytes, mime.takeIf { it.startsWith("image/") } ?: "image/png")
    }

    private val GEMINI_IMAGE_ASPECT_RATIOS = setOf(
        "1:1", "1:4", "1:8", "2:3", "3:2", "3:4", "4:1", "4:3", "4:5", "5:4",
        "8:1", "9:16", "16:9", "21:9",
    )
    private const val MAX_GENERATED_IMAGE_BYTES = 25 * 1024 * 1024

    /**
     * 一条协议无关消息可展开为多条 contents：带工具调用的 assistant 展开为
     * "model(functionCall) + user(functionResponse)" 两条。
     */
    internal fun encodeContents(m: ApiMessage): List<JSONObject> {
        if (m.toolCalls.isNotEmpty()) {
            val modelParts = JSONArray()
            if (m.content.isNotBlank()) modelParts.put(JSONObject().put("text", m.content))
            m.toolCalls.forEach { c ->
                modelParts.put(JSONObject().apply {
                    put("functionCall", JSONObject()
                        .put("name", c.name)
                        .put("args", runCatching { JSONObject(c.arguments) }.getOrDefault(JSONObject())))
                    if (c.extra.isNotBlank()) put("thoughtSignature", c.extra)
                })
            }
            val respParts = JSONArray()
            m.toolCalls.forEach { c ->
                respParts.put(JSONObject().put("functionResponse", JSONObject()
                    .put("name", c.name)
                    .put("response", JSONObject().put("result", c.result))))
            }
            return listOf(
                JSONObject().put("role", "model").put("parts", modelParts),
                JSONObject().put("role", "user").put("parts", respParts),
            )
        }
        return listOf(JSONObject().apply {
            put("role", if (m.role == "assistant") "model" else "user")
            put("parts", encodeParts(m))
        })
    }

    private fun encodeParts(m: ApiMessage): JSONArray {
        val parts = JSONArray()
        if (m.content.isNotBlank() || m.images.isEmpty()) {
            parts.put(JSONObject().put("text", m.content))
        }
        m.images.forEach { img ->
            parts.put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", img.mimeType)
                .put("data", img.base64)))
        }
        return parts
    }
}
