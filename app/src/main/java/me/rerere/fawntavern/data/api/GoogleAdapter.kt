package me.rerere.fawntavern.data.api

import me.rerere.fawntavern.data.api.SseClient.strOr
import org.json.JSONArray
import org.json.JSONObject

/** Google Gemini 协议: POST {base}/models/{m}:streamGenerateContent?alt=sse */
internal object GoogleAdapter : ProviderAdapter {

    override fun stream(
        provider: ApiProvider, modelId: String,
        messages: List<ApiMessage>, params: GenParams?,
        onDelta: (String, String) -> Unit,
        stopped: () -> Unit, onCall: (okhttp3.Call) -> Unit,
    ) {
        // 开头连续 system → systemInstruction；对话中间的 system（深度注入等）降级为 user
        val (system, rest) = splitLeadingSystem(messages)
        val merged = mergeConsecutive(rest.map { if (it.role == "system") it.copy(role = "user") else it })
        val body = JSONObject().apply {
            if (system.isNotBlank()) {
                put("systemInstruction", JSONObject().put("parts",
                    JSONArray().put(JSONObject().put("text", system))))
            }
            params?.let { p ->
                val cfg = JSONObject()
                p.temperature?.let { cfg.put("temperature", it.toDouble()) }
                p.topP?.let { cfg.put("topP", it.toDouble()) }
                p.topK?.takeIf { it > 0 }?.let { cfg.put("topK", it) }
                p.maxTokens?.let { cfg.put("maxOutputTokens", it) }
                p.frequencyPenalty?.let { cfg.put("frequencyPenalty", it.toDouble()) }
                p.presencePenalty?.let { cfg.put("presencePenalty", it.toDouble()) }
                p.seed?.let { cfg.put("seed", it) }
                if (cfg.length() > 0) put("generationConfig", cfg)
            }
            put("contents", JSONArray().apply {
                merged.forEach { m ->
                    put(JSONObject().apply {
                        put("role", if (m.role == "assistant") "model" else "user")
                        put("parts", encodeParts(m))
                    })
                }
            })
        }
        SseClient.post(
            url = "${provider.baseUrl.trimEnd('/')}/models/$modelId:streamGenerateContent?alt=sse&key=${provider.apiKey}",
            headers = emptyMap(),
            body = body,
            stopped = stopped,
            onCall = onCall,
        ) { data ->
            val obj = JSONObject(data)
            val parts = obj.optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return@post
            for (i in 0 until parts.length()) {
                val text = parts.optJSONObject(i)?.strOr("text") ?: ""
                if (text.isNotEmpty()) onDelta(text, "")
            }
        }
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
