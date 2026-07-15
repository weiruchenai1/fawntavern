package me.rerere.stapp.data.api

import me.rerere.stapp.data.api.SseClient.strOr
import org.json.JSONArray
import org.json.JSONObject

/** Claude 协议: POST {base}/messages */
internal object ClaudeAdapter : ProviderAdapter {

    override fun stream(
        provider: ApiProvider, modelId: String,
        messages: List<ApiMessage>, params: GenParams?,
        onDelta: (String, String) -> Unit,
        stopped: () -> Unit, onCall: (okhttp3.Call) -> Unit,
    ) {
        // 开头连续 system → 顶层 system 参数；对话中间的 system（深度注入等）降级为 user
        val (system, rest) = splitLeadingSystem(messages)
        val merged = mergeConsecutive(rest.map { if (it.role == "system") it.copy(role = "user") else it })
        // Claude 要求消息以 user 开头，去掉开头的 assistant 消息（如角色开场白）
        val msgs = merged.dropWhile { it.role != "user" }
        val body = JSONObject().apply {
            put("model", modelId)
            put("max_tokens", params?.maxTokens ?: 8192)
            put("stream", true)
            params?.temperature?.let { put("temperature", it.toDouble().coerceIn(0.0, 1.0)) }
            params?.topP?.let { put("top_p", it.toDouble()) }
            params?.topK?.takeIf { it > 0 }?.let { put("top_k", it) }
            if (system.isNotBlank()) put("system", system)
            put("messages", JSONArray().apply {
                msgs.forEach { m -> put(encodeMessage(m)) }
            })
        }
        SseClient.post(
            url = "${provider.baseUrl.trimEnd('/')}/messages",
            headers = mapOf(
                "x-api-key" to provider.apiKey,
                "anthropic-version" to "2023-06-01",
            ),
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
