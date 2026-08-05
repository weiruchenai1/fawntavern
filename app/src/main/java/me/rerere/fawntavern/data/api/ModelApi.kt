package me.rerere.fawntavern.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 模型提供商 API 客户端（列出模型、测试连接、查询余额） */
object ModelApi {

    /**
     * 获取提供商的可用模型列表。能力信息优先取接口自己给的（OpenRouter 的
     * architecture/supported_parameters、Gemini 的 thinking），接口没给的才按 ID 猜
     * —— OpenAI 官方与绝大多数兼容网关的 /models 只返回一个 id，猜是唯一选择。
     */
    suspend fun listModels(provider: ApiProvider): List<ModelInfo> = withContext(Dispatchers.IO) {
        when (provider.type) {
            "google" -> listGoogleModels(provider)
            "claude" -> listClaudeModels(provider)
            else -> listOpenAIModels(provider)
        }.sortedBy { it.id }
    }

    /** 查询提供商余额：GET {baseUrl}{balancePath}，按 balanceJsonKey 取值 */
    suspend fun getBalance(provider: ApiProvider): String = withContext(Dispatchers.IO) {
        val path = provider.balancePath
        val url = if (path.startsWith("http")) path else "${provider.baseUrl.trimEnd('/')}$path"
        val body = httpGet(url, headers = mapOf("Authorization" to "Bearer ${provider.apiKey}"))
        val value = resolveJsonKey(JSONObject(body), provider.balanceJsonKey)
        value.toFloatOrNull()?.let { "%.2f".format(it) } ?: value
    }

    /** 解析 JSON 键路径，如 "data.totalBalance"、"balance_infos[0].total_balance" */
    private fun resolveJsonKey(root: JSONObject, key: String): String {
        var cur: Any? = root
        for (seg in key.trim().split('.')) {
            val name = seg.substringBefore('[').trim()
            if (name.isNotEmpty()) cur = (cur as? JSONObject)?.opt(name)
            var rest = seg.substringAfter('[', "")
            while (rest.isNotEmpty()) {
                val idx = rest.substringBefore(']').trim().toIntOrNull() ?: return ""
                cur = (cur as? JSONArray)?.opt(idx)
                rest = rest.substringAfter(']', "").substringAfter('[', "")
            }
        }
        return if (cur == null || cur == JSONObject.NULL) "" else cur.toString()
    }

    // ── OpenAI 兼容: GET {base}/models ───────────────────

    private fun listOpenAIModels(provider: ApiProvider): List<ModelInfo> {
        val body = httpGet(
            url = "${provider.baseUrl.trimEnd('/')}/models",
            headers = mapOf("Authorization" to "Bearer ${provider.apiKey}"),
        )
        val json = JSONObject(body)
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { openAiModel(data.optJSONObject(it)) }
    }

    /** OpenRouter 一类网关会附带能力字段，照抄；只有 id 的（OpenAI 官方等）退回按 ID 猜 */
    private fun openAiModel(obj: JSONObject?): ModelInfo? {
        val id = obj?.optString("id")?.takeIf { it.isNotBlank() } ?: return null
        val guess = modelInfoOf(id)
        val arch = obj.optJSONObject("architecture")
        val params = obj.optJSONArray("supported_parameters").strings()
        if (arch == null && params.isEmpty()) return guess
        val input = arch?.optJSONArray("input_modalities").strings()
        val output = arch?.optJSONArray("output_modalities").strings()
        return guess.copy(
            inputModalities = if (input.isEmpty()) guess.inputModalities else modalitiesOf(input),
            outputModalities = if (output.isEmpty()) guess.outputModalities else modalitiesOf(output),
            abilities = if (params.isEmpty()) guess.abilities else buildList {
                if (params.any { it == "tools" || it == "tool_choice" }) add(ModelAbility.TOOL)
                if (params.any { it == "reasoning" || it == "include_reasoning" }) add(ModelAbility.REASONING)
            },
        )
    }

    // ── Google Gemini: GET {base}/models?key= ────────────

    private fun listGoogleModels(provider: ApiProvider): List<ModelInfo> {
        val body = httpGet(
            url = "${provider.baseUrl.trimEnd('/')}/models?key=${provider.apiKey}&pageSize=1000",
            headers = emptyMap(),
        )
        val json = JSONObject(body)
        val models = json.optJSONArray("models") ?: return emptyList()
        return (0 until models.length()).mapNotNull { googleModel(models.optJSONObject(it)) }
    }

    private fun googleModel(obj: JSONObject?): ModelInfo? {
        val id = obj?.optString("name")?.removePrefix("models/")?.takeIf { it.isNotBlank() } ?: return null
        // 只留能用于对话的模型：嵌入、token 计数一类在本 App 里用不上
        val methods = obj.optJSONArray("supportedGenerationMethods").strings()
        if (methods.isNotEmpty() && methods.none { it.equals("generateContent", ignoreCase = true) }) return null
        val guess = modelInfoOf(id)
        // 官方列表会标明该模型是否支持思考，比按 ID 猜准
        if (!obj.has("thinking")) return guess
        val thinking = obj.optBoolean("thinking")
        return guess.copy(abilities = when {
            thinking -> (guess.abilities + ModelAbility.REASONING).distinct().sortedBy { it.ordinal }
            else -> guess.abilities - ModelAbility.REASONING
        })
    }

    // ── Claude: GET {base}/models ────────────────────────

    private fun listClaudeModels(provider: ApiProvider): List<ModelInfo> {
        val body = httpGet(
            url = "${provider.baseUrl.trimEnd('/')}/models?limit=1000",
            headers = mapOf(
                "x-api-key" to provider.apiKey,
                "anthropic-version" to "2023-06-01",
            ),
        )
        val json = JSONObject(body)
        val data = json.optJSONArray("data") ?: return emptyList()
        // Anthropic 只返回 id 与 display_name，能力全靠猜
        return (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { modelInfoOf(it) }
        }
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList()
        else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

    /** 接口给的模态名（text/image/file…）只映射本 App 认识的两种，文本恒有 */
    private fun modalitiesOf(names: List<String>): List<Modality> = buildList {
        add(Modality.TEXT)
        if (names.any { it.equals("image", ignoreCase = true) }) add(Modality.IMAGE)
    }

    private fun httpGet(url: String, headers: Map<String, String>): String {
        val request = okhttp3.Request.Builder()
            .url(url)
            .get()
            .apply { headers.forEach { (k, v) -> if (v.isNotBlank()) header(k, v) } }
            .build()
        Http.client.newCall(request).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${body.take(300)}")
            }
            return body
        }
    }
}
