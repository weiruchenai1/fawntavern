package me.rerere.fawntavern.data.api

import me.rerere.fawntavern.data.api.SseClient.strOr
import org.json.JSONArray
import org.json.JSONObject

/** Google Gemini 协议: POST {base}/models/{m}:streamGenerateContent?alt=sse */
internal object GoogleAdapter : ProviderAdapter {

    override fun stream(
        provider: ApiProvider, model: ModelInfo,
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
                        // Gemini 3 起用 thinkingLevel 枚举取代 token 预算
                        put("thinkingLevel", when (level) {
                            ReasoningLevel.OFF -> "minimal"
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
            if (cfg.length() > 0) put("generationConfig", cfg)
            // 服务端内置工具（搜索 / URL 上下文），由模型详情逐个开关
            if (model.tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    if (BuiltInTool.SEARCH in model.tools) put(JSONObject().put("google_search", JSONObject()))
                    if (BuiltInTool.URL_CONTEXT in model.tools) put(JSONObject().put("url_context", JSONObject()))
                })
            }
            put("contents", JSONArray().apply {
                merged.forEach { m ->
                    put(JSONObject().apply {
                        put("role", if (m.role == "assistant") "model" else "user")
                        put("parts", encodeParts(m))
                    })
                }
            })
            applyCustomBodies(model)
        }
        SseClient.post(
            url = "${provider.baseUrl.trimEnd('/')}/models/${model.id}:streamGenerateContent?alt=sse&key=${provider.apiKey}",
            headers = model.applyHeaders(emptyMap()),
            body = body,
            stopped = stopped,
            onCall = onCall,
        ) { data ->
            val obj = JSONObject(data)
            val parts = obj.optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return@post
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val text = part.strOr("text")
                if (text.isEmpty()) continue
                // 思考内容与正文混在同一个 parts 数组里，只靠 thought=true 区分（不分流会当正文输出）
                if (part.optBoolean("thought")) onDelta("", text) else onDelta(text, "")
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
