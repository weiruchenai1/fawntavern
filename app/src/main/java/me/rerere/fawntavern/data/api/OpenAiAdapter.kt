package me.rerere.fawntavern.data.api

import java.util.Base64
import me.rerere.fawntavern.data.api.SseClient.strOr
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI 兼容协议: POST {base}/chat/completions */
internal object OpenAiAdapter : ProviderAdapter {

    override fun stream(
        provider: ApiProvider, model: ModelInfo,
        messages: List<ApiMessage>, params: GenParams?, tools: List<ToolSpec>,
        onDelta: (String, String) -> Unit,
        stopped: () -> Unit, onCall: (okhttp3.Call) -> Unit,
    ): StreamEnd {
        if (isImageGenerationModel(model)) {
            return generateImages(provider, model, messages, stopped, onCall)
        }
        if (provider.useResponseApi) {
            return OpenAiResponsesAdapter.stream(
                provider, model, messages, params, tools, onDelta, stopped, onCall,
            )
        }
        val body = JSONObject().apply {
            put("model", model.id)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
            params?.temperature?.let { put("temperature", it.toDouble()) }
            params?.topP?.let { put("top_p", it.toDouble()) }
            params?.maxTokens?.let { put("max_tokens", it) }
            params?.frequencyPenalty?.let { put("frequency_penalty", it.toDouble()) }
            params?.presencePenalty?.let { put("presence_penalty", it.toDouble()) }
            params?.seed?.let { put("seed", it) }
            putReasoning(provider.baseUrl, model.id, params?.reasoning ?: ReasoningLevel.AUTO)
            putSearch(provider.baseUrl, BuiltInTool.SEARCH in model.tools)
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    tools.forEach { t ->
                        put(JSONObject().put("type", "function").put("function", JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", JSONObject(t.parametersSchema))))
                    }
                })
            }
            put("messages", JSONArray().apply {
                messages.forEach { m -> encodeMessage(m).forEach { put(it) } }
            })
            applyCustomBodies(model)
        }
        // 流式工具调用按 index 分片到达（id/name 只在首片，arguments 逐片追加），此处累积拼装
        val callsByIndex = sortedMapOf<Int, Triple<String, String, StringBuilder>>()
        var promptTokens = 0
        var completionTokens = 0
        SseClient.post(
            url = "${provider.baseUrl.trimEnd('/')}/chat/completions",
            headers = model.applyHeaders(mapOf("Authorization" to "Bearer ${provider.apiKey}")),
            body = body,
            stopped = stopped,
            onCall = onCall,
        ) { data ->
            if (data == "[DONE]") return@post
            val obj = JSONObject(data)
            obj.optJSONObject("usage")?.let { usage ->
                promptTokens = usage.optInt("prompt_tokens", usage.optInt("input_tokens", promptTokens))
                completionTokens = usage.optInt("completion_tokens", usage.optInt("output_tokens", completionTokens))
            }
            val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return@post
            val content = delta.strOr("content")
            val reasoning = delta.strOr("reasoning_content").ifEmpty { delta.strOr("reasoning") }
            if (content.isNotEmpty() || reasoning.isNotEmpty()) onDelta(content, reasoning)
            delta.optJSONArray("tool_calls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val tc = arr.optJSONObject(i) ?: continue
                    val idx = tc.optInt("index", i)
                    val fn = tc.optJSONObject("function")
                    val prev = callsByIndex[idx]
                    val id = tc.strOr("id").ifEmpty { prev?.first ?: "" }
                    val name = (fn?.strOr("name") ?: "").ifEmpty { prev?.second ?: "" }
                    val args = prev?.third ?: StringBuilder()
                    fn?.strOr("arguments")?.let { args.append(it) }
                    callsByIndex[idx] = Triple(id, name, args)
                }
            }
        }
        return StreamEnd(
            toolCalls = callsByIndex.values
                .filter { it.second.isNotBlank() }
                .mapIndexed { i, (id, name, args) ->
                    ApiToolCall(id = id.ifBlank { "call_$i" }, name = name, arguments = args.toString())
                },
            promptTokens = promptTokens,
            completionTokens = completionTokens,
        )
    }

    private fun generateImages(
        provider: ApiProvider,
        model: ModelInfo,
        messages: List<ApiMessage>,
        stopped: () -> Unit,
        onCall: (okhttp3.Call) -> Unit,
    ): StreamEnd {
        val prompt = imageGenerationPrompt(messages)
        require(prompt.isNotBlank()) { "Image generation requires a text prompt" }
        val body = JSONObject()
            .put("model", model.id)
            .put("prompt", prompt)
            .apply { applyCustomBodies(model) }
        val response = SseClient.postJson(
            url = "${provider.baseUrl.trimEnd('/')}/images/generations",
            headers = model.applyHeaders(mapOf("Authorization" to "Bearer ${provider.apiKey}")),
            body = body,
            stopped = stopped,
            onCall = onCall,
        )
        val images = parseGeneratedImages(response) { url ->
            stopped()
            downloadGeneratedImage(url, stopped, onCall)
        }
        check(images.isNotEmpty()) { "Image generation returned no images" }
        return StreamEnd(generatedImages = images)
    }

    private fun downloadGeneratedImage(
        url: String,
        stopped: () -> Unit,
        onCall: (okhttp3.Call) -> Unit,
    ): GeneratedImage {
        val request = Request.Builder().url(url).header("Accept", "image/*").build()
        val call = Http.client.newCall(request)
        onCall(call)
        call.execute().use { response ->
            stopped()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: failed to download generated image")
            }
            val body = response.body
            val length = body.contentLength()
            check(length <= MAX_GENERATED_IMAGE_BYTES || length < 0) { "Generated image is too large" }
            val responseMime = body.contentType()?.toString()?.substringBefore(';')
            val bytes = body.bytes()
            check(bytes.size <= MAX_GENERATED_IMAGE_BYTES) { "Generated image is too large" }
            return GeneratedImage(
                bytes = bytes,
                mimeType = responseMime?.takeIf { it.startsWith("image/") } ?: inferImageMime(bytes),
            )
        }
    }

    internal fun isImageGenerationModel(model: ModelInfo): Boolean =
        Modality.IMAGE in model.outputModalities

    internal fun imageGenerationPrompt(messages: List<ApiMessage>): String =
        messages.lastOrNull { it.role == "user" && it.content.isNotBlank() }?.content
            ?: messages.lastOrNull { it.content.isNotBlank() }?.content.orEmpty()

    internal fun parseGeneratedImages(
        response: JSONObject,
        download: (String) -> GeneratedImage,
    ): List<GeneratedImage> {
        val data = response.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val encoded = item.strOr("b64_json")
                if (encoded.isNotBlank()) {
                    val payload = encoded.substringAfter(',', encoded)
                    check(payload.length <= MAX_GENERATED_IMAGE_BASE64_CHARS) {
                        "Generated image is too large"
                    }
                    val bytes = try {
                        Base64.getDecoder().decode(payload)
                    } catch (error: IllegalArgumentException) {
                        throw IllegalStateException("Image generation returned invalid base64 data", error)
                    }
                    check(bytes.size <= MAX_GENERATED_IMAGE_BYTES) { "Generated image is too large" }
                    val dataMime = encoded.takeIf { it.startsWith("data:") }
                        ?.substringAfter("data:")?.substringBefore(';')
                    add(GeneratedImage(bytes, dataMime ?: inferImageMime(bytes)))
                    continue
                }
                item.strOr("url").takeIf { it.isNotBlank() }?.let { add(download(it)) }
            }
        }
    }

    private fun inferImageMime(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
        ) -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/png"
    }

    private const val MAX_GENERATED_IMAGE_BYTES = 25 * 1024 * 1024
    private const val MAX_GENERATED_IMAGE_BASE64_CHARS =
        ((MAX_GENERATED_IMAGE_BYTES + 2) / 3) * 4

    /**
     * 思考预算：OpenAI 兼容阵营各家字段互不相同（无统一标准），按 baseUrl 主机名分流；
     * 认不出的主机走 OpenAI 官方的 reasoning_effort。AUTO 什么都不发。
     */
    private fun JSONObject.putReasoning(baseUrl: String, modelId: String, level: ReasoningLevel) {
        if (level == ReasoningLevel.AUTO) return
        val host = try { java.net.URI(baseUrl).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        when {
            // OpenRouter：关闭用文档定义的 enabled:false（effort 枚举不含 none）
            host.endsWith("openrouter.ai") -> put("reasoning", JSONObject().apply {
                if (level.isEnabled) put("effort", level.effort) else put("enabled", false)
            })
            // 阿里云百炼（通义千问）/ 硅基流动：开关 + token 预算
            host.endsWith("dashscope.aliyuncs.com") || host.endsWith("siliconflow.cn") -> {
                put("enable_thinking", level.isEnabled)
                if (level.isEnabled) put("thinking_budget", level.budgetTokens)
            }
            // 火山方舟（豆包）/ 智谱 / Moonshot：thinking.type 开关
            host.endsWith("volces.com") || host.endsWith("bigmodel.cn") || host.endsWith("moonshot.cn") ->
                put("thinking", JSONObject().put("type", if (level.isEnabled) "enabled" else "disabled"))
            host.endsWith("deepseek.com") -> {
                put("thinking", JSONObject().put("type", if (level.isEnabled) "enabled" else "disabled"))
                if (level.isEnabled) put("reasoning_effort", level.effort)
            }
            // OpenAI 官方及各类兼容网关：通用取值只有 low/medium/high（xhigh 降级为 high）。
            // 关闭档按模型 ID 分流 —— gpt-5 系发 "none"；已知厂牌的混合思考模型（DeepSeek/Qwen/
            // GLM/Kimi 等常经中转网关代理，网关大多把未知字段透传给上游）发各家惯用的关闭字段，
            // OpenAI 官方不会服务这些模型名、无冲突；其余模型什么都不发 ——
            // 发 "low" 会在默认不思考的混合模型上反而主动开启思考（对齐 Kelivo 的做法）
            else -> when {
                level == ReasoningLevel.OFF -> {
                    val id = modelId.lowercase()
                    when {
                        id.contains("gpt-5") -> put("reasoning_effort", "none")
                        id.contains("deepseek") || id.contains("glm") ||
                            id.contains("kimi") || id.contains("moonshot") || id.contains("doubao") ->
                            put("thinking", JSONObject().put("type", "disabled"))
                        id.contains("qwen") || id.contains("qwq") || id.contains("hunyuan") ->
                            put("enable_thinking", false)
                    }
                }
                level == ReasoningLevel.XHIGH -> put("reasoning_effort", "high")
                else -> put("reasoning_effort", level.effort)
            }
        }
    }

    /**
     * 服务端联网搜索：OpenAI 兼容阵营同样没有统一字段，按主机名分流；
     * 认不出的主机在模型详情里就开不了这个开关（[openAiSearchStyle]），故这里必然是已知的一种。
     */
    private fun JSONObject.putSearch(baseUrl: String, enabled: Boolean) {
        if (!enabled) return
        when (openAiSearchStyle(baseUrl)) {
            OpenAiSearchStyle.OPENROUTER ->
                put("plugins", JSONArray().put(JSONObject().put("id", "web")))
            OpenAiSearchStyle.DASHSCOPE -> put("enable_search", true)
            OpenAiSearchStyle.ZHIPU -> put("tools", JSONArray().put(JSONObject()
                .put("type", "web_search")
                .put("web_search", JSONObject().put("enable", true))))
            null -> {}
        }
    }

    /**
     * 一条协议无关消息可展开为多条协议消息：带工具调用的 assistant 展开为
     * "assistant(tool_calls) + 每个调用一条 role=tool 结果"。
     * 纯文本时 content 用字符串（兼容面最大）；带图片时用多 part 数组。
     */
    internal fun encodeMessage(m: ApiMessage): List<JSONObject> {
        if (m.toolCalls.isNotEmpty()) {
            val out = mutableListOf<JSONObject>()
            out += JSONObject().apply {
                put("role", "assistant")
                // 部分网关要求 content 字段必须存在，空文本时置 null
                if (m.content.isNotBlank()) put("content", m.content) else put("content", JSONObject.NULL)
                put("tool_calls", JSONArray().apply {
                    m.toolCalls.forEach { c ->
                        put(JSONObject()
                            .put("id", c.id)
                            .put("type", "function")
                            .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments)))
                    }
                })
            }
            m.toolCalls.forEach { c ->
                out += JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", c.id)
                    .put("content", c.result)
            }
            return out
        }
        if (m.images.isEmpty()) {
            return listOf(JSONObject().put("role", m.role).put("content", m.content))
        }
        val parts = JSONArray()
        if (m.content.isNotBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", m.content))
        }
        m.images.forEach { img ->
            parts.put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:${img.mimeType};base64,${img.base64}")))
        }
        return listOf(JSONObject().put("role", m.role).put("content", parts))
    }
}
