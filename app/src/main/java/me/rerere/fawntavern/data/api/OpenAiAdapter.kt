package me.rerere.fawntavern.data.api

import me.rerere.fawntavern.data.api.SseClient.strOr
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI 兼容协议: POST {base}/chat/completions */
internal object OpenAiAdapter : ProviderAdapter {

    override fun stream(
        provider: ApiProvider, modelId: String,
        messages: List<ApiMessage>, params: GenParams?,
        onDelta: (String, String) -> Unit,
        stopped: () -> Unit, onCall: (okhttp3.Call) -> Unit,
    ) {
        val body = JSONObject().apply {
            put("model", modelId)
            put("stream", true)
            params?.temperature?.let { put("temperature", it.toDouble()) }
            params?.topP?.let { put("top_p", it.toDouble()) }
            params?.maxTokens?.let { put("max_tokens", it) }
            params?.frequencyPenalty?.let { put("frequency_penalty", it.toDouble()) }
            params?.presencePenalty?.let { put("presence_penalty", it.toDouble()) }
            params?.seed?.let { put("seed", it) }
            put("messages", JSONArray().apply {
                messages.forEach { m -> put(encodeMessage(m)) }
            })
        }
        SseClient.post(
            url = "${provider.baseUrl.trimEnd('/')}/chat/completions",
            headers = mapOf("Authorization" to "Bearer ${provider.apiKey}"),
            body = body,
            stopped = stopped,
            onCall = onCall,
        ) { data ->
            if (data == "[DONE]") return@post
            val obj = JSONObject(data)
            val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return@post
            val content = delta.strOr("content")
            val reasoning = delta.strOr("reasoning_content").ifEmpty { delta.strOr("reasoning") }
            if (content.isNotEmpty() || reasoning.isNotEmpty()) onDelta(content, reasoning)
        }
    }

    /** 纯文本时 content 用字符串（兼容面最大）；带图片时用多 part 数组 */
    private fun encodeMessage(m: ApiMessage): JSONObject {
        if (m.images.isEmpty()) {
            return JSONObject().put("role", m.role).put("content", m.content)
        }
        val parts = JSONArray()
        if (m.content.isNotBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", m.content))
        }
        m.images.forEach { img ->
            parts.put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:${img.mimeType};base64,${img.base64}")))
        }
        return JSONObject().put("role", m.role).put("content", parts)
    }
}
