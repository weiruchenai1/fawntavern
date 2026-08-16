package me.rerere.fawntavern.data.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** SSE POST 客户端（OkHttp）：各 ProviderAdapter 共用 */
internal object SseClient {

    private val JSON = "application/json".toMediaType()

    /**
     * POST JSON 并逐条回调 SSE data 行。返回前保证连接已关闭；
     * HTTP 错误抛 IllegalStateException。stopped 在每行前调用，用于响应用户停止；
     * onCall 在发起请求前回调 Call，供外部在阻塞读期间 cancel() 立即中断。
     */
    fun post(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        stopped: () -> Unit = {},
        onCall: (okhttp3.Call) -> Unit = {},
        onData: (String) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "text/event-stream")
            .apply { headers.forEach { (k, v) -> if (v.isNotBlank()) header(k, v) } }
            .build()
        val call = Http.sseClient.newCall(request)
        onCall(call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body.string()
                throw IllegalStateException("HTTP ${resp.code}: ${err.take(300)}")
            }
            resp.body.charStream().buffered().useLines { lines ->
                for (line in lines) {
                    stopped()  // 检查停止标记 — 确保即使模型思考期间也能响应
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isNotEmpty()) onData(data)
                }
            }
        }
    }

    /** POST JSON 并一次性读取 JSON 响应，供图片生成等非 SSE 端点使用。 */
    fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        stopped: () -> Unit = {},
        onCall: (okhttp3.Call) -> Unit = {},
    ): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .apply { headers.forEach { (k, v) -> if (v.isNotBlank()) header(k, v) } }
            .build()
        val call = Http.sseClient.newCall(request)
        onCall(call)
        call.execute().use { response ->
            stopped()
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${text.take(300)}")
            }
            return JSONObject(text)
        }
    }

    fun JSONObject.strOr(key: String): String =
        if (has(key) && !isNull(key)) optString(key, "") else ""
}
