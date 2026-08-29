package me.rerere.fawntavern.data.api

import me.rerere.fawntavern.data.api.SseClient.strOr
import org.json.JSONArray
import org.json.JSONObject

/** Claude 协议: POST {base}/messages */
internal object ClaudeAdapter : ProviderAdapter {

    override fun stream(
        provider: ApiProvider, model: ModelInfo,
        messages: List<ApiMessage>, params: GenParams?, tools: List<ToolSpec>,
        onDelta: (String, String) -> Unit,
        stopped: () -> Unit, onCall: (okhttp3.Call) -> Unit,
    ): StreamEnd {
        // 开头连续 system → 顶层 system 参数；对话中间的 system（深度注入等）降级为 user
        val (system, rest) = splitLeadingSystem(messages)
        val merged = mergeConsecutive(rest.map { if (it.role == "system") it.copy(role = "user") else it })
        // Claude 要求消息以 user 开头，去掉开头的 assistant 消息（如角色开场白）
        val msgs = merged.dropWhile { it.role != "user" }
        val level = params?.reasoning ?: ReasoningLevel.AUTO
        val adaptive = useAdaptiveThinking(model.id)
        // budget_tokens 必须小于 max_tokens，预算比上限还大时把上限抬起来（否则整个请求被拒）
        val maxTokens = params?.maxTokens ?: 8192
        val effMaxTokens =
            if (level.isEnabled && !adaptive) maxOf(maxTokens, level.budgetTokens + 4096) else maxTokens
        val body = JSONObject().apply {
            put("model", model.id)
            put("max_tokens", effMaxTokens)
            put("stream", true)
            // 开启思考时 Claude 不接受自定义采样参数（temperature 必须为 1、top_k 直接被拒），全部略过
            if (!level.isEnabled) {
                params?.temperature?.let { put("temperature", it.roundedSamplingDouble().coerceIn(0.0, 1.0)) }
                params?.topP?.let { put("top_p", it.roundedSamplingDouble()) }
                params?.topK?.takeIf { it > 0 }?.let { put("top_k", it) }
            }
            when {
                level == ReasoningLevel.AUTO -> {}
                // Fable/Mythos 系思考常开、不接受 disabled：关闭档退化为不发字段
                !level.isEnabled -> if (!thinkingAlwaysOn(model.id)) {
                    put("thinking", JSONObject().put("type", "disabled"))
                }
                adaptive -> {
                    put("thinking", JSONObject().put("type", "adaptive").put("display", "summarized"))
                    put("output_config", JSONObject().put("effort", level.effort))
                }
                else -> put("thinking", JSONObject()
                    .put("type", "enabled").put("budget_tokens", level.budgetTokens))
            }
            if (system.isNotBlank()) put("system", system)
            // 服务端搜索工具与 App 函数工具同为 tools 数组成员，可以并存
            val toolArr = JSONArray()
            if (BuiltInTool.SEARCH in model.tools) {
                toolArr.put(JSONObject()
                    .put("type", "web_search_20250305")
                    .put("name", "web_search"))
            }
            tools.forEach { t ->
                toolArr.put(JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("input_schema", JSONObject(t.parametersSchema)))
            }
            if (toolArr.length() > 0) put("tools", toolArr)
            put("messages", JSONArray().apply {
                msgs.forEach { m -> encodeMessage(m).forEach { put(it) } }
            })
            applyCustomBodies(model)
        }
        // 按 index 跟踪内容块：thinking 的 signature 与 tool_use 的完整 input 都要在
        // 块级累积，收尾时原样重建 content 数组供下一轮回显（Claude 校验 thinking 签名）
        class Block(val type: String) {
            val text = StringBuilder()      // text / thinking 正文
            val inputJson = StringBuilder() // tool_use 的 input_json_delta 分片
            var signature = ""              // thinking 块签名
            var id = ""                     // tool_use 调用 id
            var name = ""                   // tool_use 工具名
            var data = ""                   // redacted_thinking 原文
        }
        val blocks = sortedMapOf<Int, Block>()
        var promptTokens = 0
        var completionTokens = 0
        var cachedTokens = 0
        val endpoint = provider.apiEndpoint("/messages")
        val snapshot = requestSnapshot(endpoint, body)
        captureRequestFailure(snapshot, stopped) {
            SseClient.post(
            url = endpoint,
            headers = model.applyHeaders(mapOf(
                "x-api-key" to provider.apiKey,
                "anthropic-version" to "2023-06-01",
            )),
            body = body,
            stopped = stopped,
            onCall = onCall,
            ) { data ->
            val obj = JSONObject(data)
            when (obj.optString("type")) {
                "message_start" -> obj.optJSONObject("message")?.optJSONObject("usage")?.let { usage ->
                    promptTokens = usage.optInt("input_tokens", promptTokens)
                    completionTokens = usage.optInt("output_tokens", completionTokens)
                    cachedTokens = usage.optInt("cache_read_input_tokens", cachedTokens)
                }
                "message_delta" -> obj.optJSONObject("usage")?.let { usage ->
                    completionTokens = usage.optInt("output_tokens", completionTokens)
                }
                "content_block_start" -> {
                    val idx = obj.optInt("index")
                    val cb = obj.optJSONObject("content_block") ?: return@post
                    val block = Block(cb.optString("type"))
                    block.id = cb.strOr("id")
                    block.name = cb.strOr("name")
                    block.data = cb.strOr("data")
                    blocks[idx] = block
                }
                "content_block_delta" -> {
                    val idx = obj.optInt("index")
                    val delta = obj.optJSONObject("delta") ?: return@post
                    val block = blocks.getOrPut(idx) { Block("text") }
                    when (delta.optString("type")) {
                        "text_delta" -> {
                            val t = delta.strOr("text")
                            block.text.append(t)
                            onDelta(t, "")
                        }
                        "thinking_delta" -> {
                            val t = delta.strOr("thinking")
                            block.text.append(t)
                            onDelta("", t)
                        }
                        "signature_delta" -> block.signature += delta.strOr("signature")
                        "input_json_delta" -> block.inputJson.append(delta.strOr("partial_json"))
                    }
                }
                "error" -> throw IllegalStateException(
                    obj.optJSONObject("error")?.optString("message") ?: "Claude API error")
            }
            }
        }
        val toolCalls = blocks.values.filter { it.type == "tool_use" }.map { b ->
            ApiToolCall(
                id = b.id,
                name = b.name,
                arguments = b.inputJson.toString().ifBlank { "{}" },
            )
        }
        if (toolCalls.isEmpty()) return StreamEnd(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
            requestSnapshot = snapshot,
        )
        // 原样重建本轮 assistant 内容块（thinking 签名 / redacted_thinking 必须逐字回显）
        val raw = JSONArray()
        blocks.values.forEach { b ->
            when (b.type) {
                "thinking" -> if (b.text.isNotEmpty() || b.signature.isNotEmpty()) {
                    raw.put(JSONObject().put("type", "thinking")
                        .put("thinking", b.text.toString())
                        .apply { if (b.signature.isNotEmpty()) put("signature", b.signature) })
                }
                "redacted_thinking" -> raw.put(JSONObject()
                    .put("type", "redacted_thinking").put("data", b.data))
                "text" -> if (b.text.isNotEmpty()) {
                    raw.put(JSONObject().put("type", "text").put("text", b.text.toString()))
                }
                "tool_use" -> raw.put(JSONObject()
                    .put("type", "tool_use")
                    .put("id", b.id)
                    .put("name", b.name)
                    .put("input", runCatching { JSONObject(b.inputJson.toString()) }.getOrDefault(JSONObject())))
            }
        }
        return StreamEnd(
            toolCalls = toolCalls,
            rawBlocks = raw.toString(),
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
            requestSnapshot = snapshot,
        )
    }

    /**
     * Opus 4.6 起思考改为 adaptive 模式 + output_config.effort 控制强度，不再接受 budget_tokens；
     * 3.7 / 4 / 4.5 则相反（只认 budget_tokens），故按模型号分流。Fable/Mythos 同为 adaptive。
     */
    private fun useAdaptiveThinking(modelId: String): Boolean =
        Regex("(opus|sonnet|haiku|fable|mythos)-(4[._-][6-9]|5)").containsMatchIn(modelId.lowercase())

    /** Fable/Mythos 系思考常开，thinking.type=disabled 会被拒 */
    private fun thinkingAlwaysOn(modelId: String): Boolean =
        modelId.lowercase().let { it.contains("fable") || it.contains("mythos") }

    /**
     * 一条协议无关消息可展开为多条协议消息：带工具调用的 assistant 展开为
     * "assistant(tool_use，优先用原始块回显签名) + user(tool_result)"。
     */
    internal fun encodeMessage(m: ApiMessage): List<JSONObject> {
        if (m.toolCalls.isNotEmpty()) {
            val content = if (m.rawBlocks.isNotBlank()) {
                runCatching { JSONArray(m.rawBlocks) }.getOrNull()
            } else null
            val assistantContent = content ?: JSONArray().apply {
                if (m.content.isNotBlank()) put(JSONObject().put("type", "text").put("text", m.content))
                m.toolCalls.forEach { c ->
                    put(JSONObject()
                        .put("type", "tool_use")
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("input", runCatching { JSONObject(c.arguments) }.getOrDefault(JSONObject())))
                }
            }
            val results = JSONArray()
            m.toolCalls.forEach { c ->
                results.put(JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", c.id)
                    .put("content", c.result))
            }
            return listOf(
                JSONObject().put("role", "assistant").put("content", assistantContent),
                JSONObject().put("role", "user").put("content", results),
            )
        }
        if (m.images.isEmpty()) {
            return listOf(JSONObject().put("role", m.role).put("content", m.content))
        }
        val blocks = JSONArray()
        m.images.forEach { img ->
            blocks.put(JSONObject().put("type", "image").put("source", JSONObject()
                .put("type", "base64")
                .put("media_type", img.mimeType)
                .put("data", img.base64)))
        }
        if (m.content.isNotBlank()) {
            blocks.put(JSONObject().put("type", "text").put("text", m.content))
        }
        return listOf(JSONObject().put("role", m.role).put("content", blocks))
    }
}
