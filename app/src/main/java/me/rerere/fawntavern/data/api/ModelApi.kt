package me.rerere.fawntavern.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 模型提供商 API 客户端（列出模型、测试连接、查询余额） */
object ModelApi {

    /** 获取提供商的可用模型列表 */
    suspend fun listModels(provider: ApiProvider): List<String> = withContext(Dispatchers.IO) {
        when (provider.type) {
            "google" -> listGoogleModels(provider)
            "claude" -> listClaudeModels(provider)
            else -> listOpenAIModels(provider)
        }
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

    private fun listOpenAIModels(provider: ApiProvider): List<String> {
        val body = httpGet(
            url = "${provider.baseUrl.trimEnd('/')}/models",
            headers = mapOf("Authorization" to "Bearer ${provider.apiKey}"),
        )
        val json = JSONObject(body)
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length())
            .mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
            .sorted()
    }

    // ── Google Gemini: GET {base}/models?key= ────────────

    private fun listGoogleModels(provider: ApiProvider): List<String> {
        val body = httpGet(
            url = "${provider.baseUrl.trimEnd('/')}/models?key=${provider.apiKey}&pageSize=1000",
            headers = emptyMap(),
        )
        val json = JSONObject(body)
        val models = json.optJSONArray("models") ?: return emptyList()
        return (0 until models.length())
            .mapNotNull { models.optJSONObject(it)?.optString("name") }
            .map { it.removePrefix("models/") }
            .filter { it.isNotBlank() }
            .sorted()
    }

    // ── Claude: GET {base}/models ────────────────────────

    private fun listClaudeModels(provider: ApiProvider): List<String> {
        val body = httpGet(
            url = "${provider.baseUrl.trimEnd('/')}/models?limit=1000",
            headers = mapOf(
                "x-api-key" to provider.apiKey,
                "anthropic-version" to "2023-06-01",
            ),
        )
        val json = JSONObject(body)
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length())
            .mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
            .sorted()
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
