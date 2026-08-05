package me.rerere.fawntavern.data.api

import me.rerere.fawntavern.data.api.SseClient.strOr
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI 兼容协议: POST {base}/chat/completions */
internal object OpenAiAdapter : ProviderAdapter {

    override fun stream(
        provider: ApiProvider, model: ModelInfo,
        messages: List<ApiMessage>, params: GenParams?,
        onDelta: (String, String) -> Unit,
        stopped: () -> Unit, onCall: (okhttp3.Call) -> Unit,
    ) {
        val body = JSONObject().apply {
            put("model", model.id)
            put("stream", true)
            params?.temperature?.let { put("temperature", it.toDouble()) }
            params?.topP?.let { put("top_p", it.toDouble()) }
            params?.maxTokens?.let { put("max_tokens", it) }
            params?.frequencyPenalty?.let { put("frequency_penalty", it.toDouble()) }
            params?.presencePenalty?.let { put("presence_penalty", it.toDouble()) }
            params?.seed?.let { put("seed", it) }
            putReasoning(provider.baseUrl, params?.reasoning ?: ReasoningLevel.AUTO)
            putSearch(provider.baseUrl, BuiltInTool.SEARCH in model.tools)
            put("messages", JSONArray().apply {
                messages.forEach { m -> put(encodeMessage(m)) }
            })
            applyCustomBodies(model)
        }
        SseClient.post(
            url = "${provider.baseUrl.trimEnd('/')}/chat/completions",
            headers = model.applyHeaders(mapOf("Authorization" to "Bearer ${provider.apiKey}")),
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

    /**
     * 思考预算：OpenAI 兼容阵营各家字段互不相同（无统一标准），按 baseUrl 主机名分流；
     * 认不出的主机走 OpenAI 官方的 reasoning_effort。AUTO 什么都不发。
     */
    private fun JSONObject.putReasoning(baseUrl: String, level: ReasoningLevel) {
        if (level == ReasoningLevel.AUTO) return
        val host = try { java.net.URI(baseUrl).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        when {
            host.endsWith("openrouter.ai") -> put("reasoning", JSONObject().apply {
                put("effort", if (level.isEnabled) level.effort else "none")
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
            // OpenAI 官方及各类兼容网关：通用取值只有 low/medium/high，
            // 关闭档没有对应值（降级为 low），xhigh 也非通用（降级为 high）
            else -> put("reasoning_effort", when (level) {
                ReasoningLevel.OFF -> "low"
                ReasoningLevel.XHIGH -> "high"
                else -> level.effort
            })
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
