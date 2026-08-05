package me.rerere.fawntavern.data.api

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.api.SseClient.strOr
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 连接测试：对指定模型发起 非流式 / 流式 / 工具调用 三种最小请求 */
object ConnectionTester {

    private val JSON = "application/json".toMediaType()

    private const val TEST_SYSTEM = "You are a helpful assistant"
    private const val TEST_USER = "hello"
    private const val TOOL_NAME = "get_current_time"
    private const val TOOL_DESC = "Get the current date and time."
    private const val TOOL_USER = "Use the get_current_time tool."

    /** 工具调用测试结果：toolName 非空表示模型发起了调用，否则 text 为模型的文本回复 */
    data class ToolCallResult(val toolName: String, val args: String, val text: String)

    /** 非流式补全，返回模型回复文本 */
    suspend fun testNonStreaming(provider: ApiProvider, modelId: String): String =
        withContext(Dispatchers.IO) { generate(provider, modelId, withTool = false).text }

    /** 工具调用测试：带一个无参工具发起非流式请求，检查模型是否调用 */
    suspend fun testToolCall(provider: ApiProvider, modelId: String): ToolCallResult =
        withContext(Dispatchers.IO) { generate(provider, modelId, withTool = true) }

    /** 流式补全（复用 ChatApi 的 SSE 通道），onDelta 回调增量文本 */
    suspend fun testStreaming(provider: ApiProvider, modelId: String, onDelta: (String) -> Unit) {
        val job = coroutineContext[Job]
        ChatApi.streamChat(
            provider, modelId,
            messages = listOf(ApiMessage("system", TEST_SYSTEM), ApiMessage("user", TEST_USER)),
            params = null,
            isCancelled = { job?.isActive == false },
        ) { content, _ -> if (content.isNotEmpty()) onDelta(content) }
    }

    private fun generate(provider: ApiProvider, modelId: String, withTool: Boolean): ToolCallResult {
        // 测试请求也套上该模型的自定义请求头/请求体，否则会出现「测试通过但聊天失败」这类误导
        val model = provider.model(modelId) ?: ModelInfo(id = modelId)
        return when (provider.type) {
            "google" -> google(provider, model, withTool)
            "claude" -> claude(provider, model, withTool)
            else -> openAi(provider, model, withTool)
        }
    }

    // ── OpenAI 兼容: POST {base}/chat/completions（stream=false） ──

    private fun openAi(provider: ApiProvider, model: ModelInfo, withTool: Boolean): ToolCallResult {
        val body = JSONObject().apply {
            put("model", model.id)
            put("stream", false)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", TEST_SYSTEM))
                .put(JSONObject().put("role", "user").put("content", if (withTool) TOOL_USER else TEST_USER)))
            if (withTool) {
                put("tools", JSONArray().put(JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", TOOL_NAME)
                        .put("description", TOOL_DESC)
                        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))))
            }
            applyCustomBodies(model)
        }
        val resp = post("${provider.baseUrl.trimEnd('/')}/chat/completions",
            model.applyHeaders(mapOf("Authorization" to "Bearer ${provider.apiKey}")), body)
        val msg = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val fn = msg?.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")
        return ToolCallResult(
            toolName = fn?.strOr("name").orEmpty(),
            args = fn?.strOr("arguments").orEmpty(),
            text = msg?.strOr("content").orEmpty(),
        )
    }

    // ── Google Gemini: POST {base}/models/{m}:generateContent ──

    private fun google(provider: ApiProvider, model: ModelInfo, withTool: Boolean): ToolCallResult {
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", TEST_SYSTEM))))
            put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", if (withTool) TOOL_USER else TEST_USER)))))
            if (withTool) {
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations",
                    JSONArray().put(JSONObject().put("name", TOOL_NAME).put("description", TOOL_DESC)))))
            }
            applyCustomBodies(model)
        }
        val resp = post("${provider.baseUrl.trimEnd('/')}/models/${model.id}:generateContent?key=${provider.apiKey}",
            model.applyHeaders(emptyMap()), body)
        val parts = resp.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        var text = ""; var name = ""; var args = ""
        for (i in 0 until (parts?.length() ?: 0)) {
            val p = parts?.optJSONObject(i) ?: continue
            text += p.strOr("text")
            p.optJSONObject("functionCall")?.let { fc ->
                name = fc.optString("name")
                args = fc.optJSONObject("args")?.toString() ?: ""
            }
        }
        return ToolCallResult(name, args, text)
    }

    // ── Claude: POST {base}/messages（不带 stream） ──

    private fun claude(provider: ApiProvider, model: ModelInfo, withTool: Boolean): ToolCallResult {
        val body = JSONObject().apply {
            put("model", model.id)
            put("max_tokens", 1024)
            put("system", TEST_SYSTEM)
            put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", if (withTool) TOOL_USER else TEST_USER)))
            if (withTool) {
                put("tools", JSONArray().put(JSONObject()
                    .put("name", TOOL_NAME)
                    .put("description", TOOL_DESC)
                    .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject()))))
            }
            applyCustomBodies(model)
        }
        val resp = post("${provider.baseUrl.trimEnd('/')}/messages",
            model.applyHeaders(mapOf(
                "x-api-key" to provider.apiKey,
                "anthropic-version" to "2023-06-01",
            )), body)
        val content = resp.optJSONArray("content")
        var text = ""; var name = ""; var args = ""
        for (i in 0 until (content?.length() ?: 0)) {
            val block = content?.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> text += block.strOr("text")
                "tool_use" -> {
                    name = block.optString("name")
                    args = block.optJSONObject("input")?.toString() ?: ""
                }
            }
        }
        return ToolCallResult(name, args, text)
    }

    // 思考模型可能长时间不返回，用读超时放宽的 sseClient 发非流式请求
    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .apply { headers.forEach { (k, v) -> if (v.isNotBlank()) header(k, v) } }
            .build()
        Http.sseClient.newCall(request).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
            return JSONObject(text)
        }
    }
}
