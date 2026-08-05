package me.rerere.fawntavern.data.api

import me.rerere.fawntavern.data.api.SseClient.strOr
import org.json.JSONArray
import org.json.JSONObject

/** Claude 协议: POST {base}/messages */
internal object ClaudeAdapter : ProviderAdapter {

    override fun stream(
        provider: ApiProvider, model: ModelInfo,
        messages: List<ApiMessage>, params: GenParams?,
        onDelta: (String, String) -> Unit,
        stopped: () -> Unit, onCall: (okhttp3.Call) -> Unit,
    ) {
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
                params?.temperature?.let { put("temperature", it.toDouble().coerceIn(0.0, 1.0)) }
                params?.topP?.let { put("top_p", it.toDouble()) }
                params?.topK?.takeIf { it > 0 }?.let { put("top_k", it) }
            }
            when {
                level == ReasoningLevel.AUTO -> {}
                !level.isEnabled -> put("thinking", JSONObject().put("type", "disabled"))
                adaptive -> {
                    put("thinking", JSONObject().put("type", "adaptive").put("display", "summarized"))
                    put("output_config", JSONObject().put("effort", level.effort))
                }
                else -> put("thinking", JSONObject()
                    .put("type", "enabled").put("budget_tokens", level.budgetTokens))
            }
            if (system.isNotBlank()) put("system", system)
            // Anthropic 的服务端搜索工具：结果由服务端消化，流里仍只回文本/思考块，无需 App 处理工具回合
            if (BuiltInTool.SEARCH in model.tools) {
                put("tools", JSONArray().put(JSONObject()
                    .put("type", "web_search_20250305")
                    .put("name", "web_search")))
            }
            put("messages", JSONArray().apply {
                msgs.forEach { m -> put(encodeMessage(m)) }
            })
            applyCustomBodies(model)
        }
        SseClient.post(
            url = "${provider.baseUrl.trimEnd('/')}/messages",
            headers = model.applyHeaders(mapOf(
                "x-api-key" to provider.apiKey,
                "anthropic-version" to "2023-06-01",
            )),
            body = body,
            stopped = stopped,
            onCall = onCall,
        ) { data ->
            val obj = JSONObject(data)
            val type = obj.optString("type")
            if (type == "content_block_delta") {
                val delta = obj.optJSONObject("delta") ?: return@post
                when (delta.optString("type")) {
                    "text_delta" -> onDelta(delta.strOr("text"), "")
                    "thinking_delta" -> onDelta("", delta.strOr("thinking"))
                }
            } else if (type == "error") {
                throw IllegalStateException(
                    obj.optJSONObject("error")?.optString("message") ?: "Claude API error")
            }
        }
    }

    /**
     * Opus 4.7 起思考改为 adaptive 模式 + output_config.effort 控制强度，不再接受 budget_tokens；
     * 3.7 / 4 / 4.5 则相反（只认 budget_tokens），故按模型号分流。
     */
    private fun useAdaptiveThinking(modelId: String): Boolean =
        Regex("(opus|sonnet|haiku)-(4[._-]7|5)").containsMatchIn(modelId.lowercase())

    private fun encodeMessage(m: ApiMessage): JSONObject {
        if (m.images.isEmpty()) {
            return JSONObject().put("role", m.role).put("content", m.content)
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
        return JSONObject().put("role", m.role).put("content", blocks)
    }
}
