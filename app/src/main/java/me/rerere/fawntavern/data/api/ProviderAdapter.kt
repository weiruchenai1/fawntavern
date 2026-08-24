package me.rerere.fawntavern.data.api

import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject

/** 实际下发给供应商的请求信息；不包含请求头，body 中的大段图片数据会被脱敏。 */
@Serializable
data class ApiRequestSnapshot(
    val endpoint: String = "",
    val body: String = "",
)

internal class ApiRequestException(
    val snapshot: ApiRequestSnapshot,
    cause: Exception,
) : Exception(cause.message, cause)

/** 一轮流式补全的收尾信息：模型若发起了工具调用，由上层执行后回传再开下一轮 */
data class StreamEnd(
    /** 模型本轮发起的工具调用（已解析完整参数）；为空 = 正常结束 */
    val toolCalls: List<ApiToolCall> = emptyList(),
    /** 协议私有的 assistant 原始内容块 JSON（见 [ApiMessage.rawBlocks]），无需回显的协议为空串 */
    val rawBlocks: String = "",
    /** 本轮请求的 token 用量；供应商未返回时保持 0，由生成层估算。 */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    /** 图片生成模型在本轮返回的图片；上层需将其持久化为聊天附件。 */
    val generatedImages: List<GeneratedImage> = emptyList(),
    /** 本轮实际发送的请求快照；不记录鉴权请求头。 */
    val requestSnapshot: ApiRequestSnapshot? = null,
)

internal fun requestSnapshot(endpoint: String, body: JSONObject): ApiRequestSnapshot =
    ApiRequestSnapshot(
        endpoint = redactEndpointSecrets(endpoint),
        body = sanitizeRequestJson(body).toString(),
    )

internal fun <T> captureRequestFailure(
    snapshot: ApiRequestSnapshot,
    stopped: () -> Unit,
    block: () -> T,
): T = try {
    block()
} catch (error: Exception) {
    // 取消请求仍应维持 Stopped 语义；其他异常附带请求快照供生成层保存。
    stopped()
    throw ApiRequestException(snapshot, error)
}

private fun redactEndpointSecrets(endpoint: String): String {
    val withoutUserInfo = Regex("^(https?://)[^/@]+@", RegexOption.IGNORE_CASE)
        .replace(endpoint) { match -> "${match.groupValues[1]}[已省略]@" }
    return Regex("(?i)([?&](?:key|api_key|apikey|access_token)=)[^&]+")
        .replace(withoutUserInfo) { match -> "${match.groupValues[1]}[已省略]" }
}

private fun sanitizeRequestJson(value: JSONObject): JSONObject = JSONObject().apply {
    val keys = value.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        put(key, sanitizeRequestValue(value.opt(key), key, value))
    }
}

private fun sanitizeRequestArray(value: JSONArray): JSONArray = JSONArray().apply {
    for (index in 0 until value.length()) {
        put(sanitizeRequestValue(value.opt(index), "", null))
    }
}

private fun sanitizeRequestValue(value: Any?, key: String, parent: JSONObject?): Any = when (value) {
    null, JSONObject.NULL -> JSONObject.NULL
    is JSONObject -> sanitizeRequestJson(value)
    is JSONArray -> sanitizeRequestArray(value)
    is String -> sanitizeRequestString(value, key, parent)
    else -> value
}

private fun sanitizeRequestString(value: String, key: String, parent: JSONObject?): String {
    if (key.lowercase() in SENSITIVE_BODY_KEYS) return "[已省略]"
    val dataUrlMatch = DATA_IMAGE_URL.matchEntire(value)
    if (dataUrlMatch != null) {
        val prefix = dataUrlMatch.groupValues[1]
        val payloadLength = dataUrlMatch.groupValues[2].length
        return "$prefix[已省略 $payloadLength 字符]"
    }
    val imageDataField = key == "data" && parent != null && (
        parent.optString("mimeType").startsWith("image/") ||
            parent.optString("mime_type").startsWith("image/") ||
            parent.optString("media_type").startsWith("image/") ||
            parent.optString("type") == "base64"
        )
    if (imageDataField || value.isLikelyLargeBase64()) {
        return "[已省略 ${value.length} 字符]"
    }
    return value
}

private fun String.isLikelyLargeBase64(): Boolean =
    length >= LARGE_BASE64_THRESHOLD && all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }

private val DATA_IMAGE_URL = Regex("^(data:image/[^;]+;base64,)(.*)$", RegexOption.DOT_MATCHES_ALL)
private val SENSITIVE_BODY_KEYS = setOf("api_key", "apikey", "access_token", "authorization")
private const val LARGE_BASE64_THRESHOLD = 4096

/**
 * 某一种 API 协议的流式聊天实现。实现类只关心协议编解码，
 * 网络与 SSE 解析统一走 [SseClient]，停止/线程调度由上层 [ChatApi] 负责。
 */
internal interface ProviderAdapter {

    /**
     * 阻塞式流式补全（调用方保证运行在 IO 线程）。
     * messages 是拼装完成的完整消息数组（system/user/assistant，可含图片与工具调用轮次）；
     * params 为采样参数（null = 全部用服务端默认）；tools 为下发给模型的工具声明（空 = 不发）。
     * 实现须在收尾处套上模型的自定义请求头/请求体（[applyHeaders] / [applyCustomBodies]），
     * 让它们能覆盖协议自己填的字段。
     * onDelta 回调增量 (正文, 思考内容)；stopped 应在处理每条事件前调用；
     * onCall 透传 OkHttp Call，供上层在阻塞读期间主动取消。
     * 返回本轮收尾信息：含未执行的工具调用时由上层执行并续轮。
     */
    fun stream(
        provider: ApiProvider,
        model: ModelInfo,
        messages: List<ApiMessage>,
        params: GenParams?,
        tools: List<ToolSpec>,
        onDelta: (content: String, reasoning: String) -> Unit,
        stopped: () -> Unit,
        onCall: (okhttp3.Call) -> Unit,
    ): StreamEnd
}
