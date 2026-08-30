package me.rerere.fawntavern.data.api

import java.security.SecureRandom
import java.util.Base64
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Gradio 命名 API 图片适配器；传输层通用，Space 特有的参数和结果由 profile 转换。 */
internal object GradioImageAdapter : ProviderAdapter {
    private val jsonMediaType = "application/json".toMediaType()
    private const val DEFAULT_COMMUNITY_API_NAME = "generate_image"
    private const val DEFAULT_OFFICIAL_API_NAME = "generate"
    private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
    private val secureRandom = SecureRandom()

    private data class QueueEndpoint(val fnIndex: Int, val triggerId: Int)

    private data class OfficialResolution(val width: Int, val height: Int, val ratio: String) {
        val value: String get() = "${width}x${height} ( $ratio )"
        val aspectRatio: Double get() = width.toDouble() / height
    }

    private val officialResolutions = mapOf(
        "1k" to listOf(
            OfficialResolution(1024, 1024, "1:1"),
            OfficialResolution(1152, 896, "9:7"),
            OfficialResolution(896, 1152, "7:9"),
            OfficialResolution(1152, 864, "4:3"),
            OfficialResolution(864, 1152, "3:4"),
            OfficialResolution(1248, 832, "3:2"),
            OfficialResolution(832, 1248, "2:3"),
            OfficialResolution(1280, 720, "16:9"),
            OfficialResolution(720, 1280, "9:16"),
            OfficialResolution(1344, 576, "21:9"),
            OfficialResolution(576, 1344, "9:21"),
        ),
        "2k" to listOf(
            OfficialResolution(1280, 1280, "1:1"),
            OfficialResolution(1440, 1120, "9:7"),
            OfficialResolution(1120, 1440, "7:9"),
            OfficialResolution(1472, 1104, "4:3"),
            OfficialResolution(1104, 1472, "3:4"),
            OfficialResolution(1536, 1024, "3:2"),
            OfficialResolution(1024, 1536, "2:3"),
            OfficialResolution(1536, 864, "16:9"),
            OfficialResolution(864, 1536, "9:16"),
            OfficialResolution(1680, 720, "21:9"),
            OfficialResolution(720, 1680, "9:21"),
        ),
        "4k" to listOf(
            OfficialResolution(1536, 1536, "1:1"),
            OfficialResolution(1728, 1344, "9:7"),
            OfficialResolution(1344, 1728, "7:9"),
            OfficialResolution(1728, 1296, "4:3"),
            OfficialResolution(1296, 1728, "3:4"),
            OfficialResolution(1872, 1248, "3:2"),
            OfficialResolution(1248, 1872, "2:3"),
            OfficialResolution(2048, 1152, "16:9"),
            OfficialResolution(1152, 2048, "9:16"),
            OfficialResolution(2016, 864, "21:9"),
            OfficialResolution(864, 2016, "9:21"),
        ),
    )

    override fun stream(
        provider: ApiProvider,
        model: ModelInfo,
        messages: List<ApiMessage>,
        params: GenParams?,
        tools: List<ToolSpec>,
        onDelta: (String, String) -> Unit,
        stopped: () -> Unit,
        onCall: (okhttp3.Call) -> Unit,
    ): StreamEnd {
        require(model.type == ModelType.IMAGE || Modality.IMAGE in model.outputModalities) {
            "Gradio provider requires an image generation model"
        }
        val prompt = imageGenerationPrompt(
            messages,
            includeContext = params?.imageGeneration?.includeContext != false,
        )
        require(prompt.isNotBlank()) { "Image generation requires a text prompt" }
        val settings = params?.imageGeneration ?: ImageGenerationSettings()
        val count = settings.count.coerceIn(1, 5)
        val firstSeed = settings.seed?.let(::normalizeSeed) ?: secureRandom.nextInt(Int.MAX_VALUE)
        val apiName = apiName(provider)
        val submitUrl = "${provider.baseUrl.trimEnd('/')}/gradio_api/call/$apiName"
        val snapshotBody = requestBody(provider.gradioImageProfile, prompt, settings, firstSeed)
        val snapshotUrl = if (provider.gradioImageProfile == GradioImageProfile.Z_IMAGE_OFFICIAL) {
            "${provider.baseUrl.trimEnd('/')}/gradio_api/queue/join"
        } else submitUrl
        val snapshot = requestSnapshot(snapshotUrl, snapshotBody)

        val images = captureRequestFailure(snapshot, stopped) {
            val queueEndpoint = if (provider.gradioImageProfile == GradioImageProfile.Z_IMAGE_OFFICIAL) {
                discoverQueueEndpoint(provider, model, apiName, stopped, onCall)
            } else null
            buildList {
                repeat(count) { index ->
                    stopped()
                    val seed = normalizeSeed(firstSeed + index)
                    val body = requestBody(provider.gradioImageProfile, prompt, settings, seed)
                    val output = if (queueEndpoint != null) {
                        runQueueTask(provider, model, body, queueEndpoint, stopped, onCall)
                    } else {
                        val eventId = submit(provider, model, submitUrl, body, stopped, onCall)
                        awaitResult(provider, model, submitUrl, eventId, stopped, onCall)
                    }
                    add(readImage(provider, output, provider.gradioImageProfile, stopped, onCall))
                }
            }
        }
        return StreamEnd(generatedImages = images, requestSnapshot = snapshot)
    }

    fun inspect(provider: ApiProvider): String {
        val apiName = apiName(provider)
        val url = "${provider.baseUrl.trimEnd('/')}/gradio_api/openapi.json"
        val request = Request.Builder().url(url).get()
            .apply { authHeaders(provider).forEach { (key, value) -> header(key, value) } }
            .build()
        Http.client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${text.take(300)}")
            check(text.contains(apiName)) { "Gradio endpoint /$apiName was not found" }
        }
        return "/$apiName"
    }

    private fun discoverQueueEndpoint(
        provider: ApiProvider,
        model: ModelInfo,
        apiName: String,
        stopped: () -> Unit,
        onCall: (okhttp3.Call) -> Unit,
    ): QueueEndpoint {
        val request = Request.Builder().url("${provider.baseUrl.trimEnd('/')}/config").get()
            .apply { requestHeaders(provider, model).forEach { (key, value) -> header(key, value) } }
            .build()
        val call = Http.client.newCall(request)
        onCall(call)
        call.execute().use { response ->
            stopped()
            val text = response.body.string()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${text.take(300)}")
            val dependencies = JSONObject(text).optJSONArray("dependencies")
                ?: throw IllegalStateException("Gradio config has no dependencies")
            for (index in 0 until dependencies.length()) {
                val dependency = dependencies.optJSONObject(index) ?: continue
                if (dependency.optString("api_name").trim('/') != apiName) continue
                val target = dependency.optJSONArray("targets")?.optJSONArray(0)
                val triggerId = target?.optInt(0, -1) ?: -1
                check(triggerId >= 0) { "Gradio endpoint /$apiName has no trigger" }
                return QueueEndpoint(
                    fnIndex = dependency.optInt("id", index),
                    triggerId = triggerId,
                )
            }
        }
        throw IllegalStateException("Gradio endpoint /$apiName was not found")
    }

    private fun runQueueTask(
        provider: ApiProvider,
        model: ModelInfo,
        body: JSONObject,
        endpoint: QueueEndpoint,
        stopped: () -> Unit,
        onCall: (okhttp3.Call) -> Unit,
    ): JSONArray {
        val baseUrl = provider.baseUrl.trimEnd('/')
        val sessionHash = queueSessionHash()
        val joinBody = JSONObject()
            .put("data", body.getJSONArray("data"))
            .put("fn_index", endpoint.fnIndex)
            .put("trigger_id", endpoint.triggerId)
            .put("session_hash", sessionHash)
            .put("event_data", JSONObject.NULL)
        val joinRequest = Request.Builder().url("$baseUrl/gradio_api/queue/join")
            .post(joinBody.toString().toRequestBody(jsonMediaType))
            .apply { requestHeaders(provider, model).forEach { (key, value) -> header(key, value) } }
            .build()
        val joinCall = Http.client.newCall(joinRequest)
        onCall(joinCall)
        joinCall.execute().use { response ->
            stopped()
            val text = response.body.string()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${text.take(300)}")
            check(JSONObject(text).optString("event_id").isNotBlank()) {
                "Gradio did not return an event id"
            }
        }

        val dataRequest = Request.Builder()
            .url("$baseUrl/gradio_api/queue/data?session_hash=$sessionHash")
            .get()
            .header("Accept", "text/event-stream")
            .apply { requestHeaders(provider, model).forEach { (key, value) -> header(key, value) } }
            .build()
        val dataCall = Http.sseClient.newCall(dataRequest)
        onCall(dataCall)
        dataCall.execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${response.body.string().take(300)}")
            }
            response.body.charStream().buffered().useLines { lines ->
                lines.forEach { line ->
                    stopped()
                    if (!line.startsWith("data:")) return@forEach
                    val data = line.removePrefix("data:").trim()
                    val message = runCatching { JSONObject(data) }.getOrNull() ?: return@forEach
                    if (message.optString("msg") == "process_completed") {
                        if (!message.optBoolean("success", false)) {
                            throw IllegalStateException(queueError(message))
                        }
                        return message.optJSONObject("output")?.optJSONArray("data")
                            ?: throw IllegalStateException("Gradio returned an invalid result")
                    }
                }
            }
        }
        throw IllegalStateException("Gradio stream closed before completion")
    }

    private fun queueError(message: JSONObject): String {
        val output = message.optJSONObject("output")
        val title = output?.optString("title").orEmpty()
            .ifBlank { message.optString("title") }
        val detail = output?.optString("error").orEmpty()
        return when {
            title.isNotBlank() && detail.isNotBlank() && title != detail -> "$title: $detail"
            detail.isNotBlank() -> detail
            title.isNotBlank() -> title
            else -> "Gradio image generation failed"
        }
    }

    private fun queueSessionHash(): String {
        val bytes = ByteArray(12)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun submit(
        provider: ApiProvider,
        model: ModelInfo,
        url: String,
        body: JSONObject,
        stopped: () -> Unit,
        onCall: (okhttp3.Call) -> Unit,
    ): String {
        val request = Request.Builder().url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .apply { requestHeaders(provider, model).forEach { (key, value) -> header(key, value) } }
            .build()
        val call = Http.client.newCall(request)
        onCall(call)
        call.execute().use { response ->
            stopped()
            val text = response.body.string()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${text.take(300)}")
            return JSONObject(text).optString("event_id").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Gradio did not return an event id")
        }
    }

    private fun awaitResult(
        provider: ApiProvider,
        model: ModelInfo,
        submitUrl: String,
        eventId: String,
        stopped: () -> Unit,
        onCall: (okhttp3.Call) -> Unit,
    ): JSONArray {
        val request = Request.Builder().url("$submitUrl/$eventId").get()
            .header("Accept", "text/event-stream")
            .apply { requestHeaders(provider, model).forEach { (key, value) -> header(key, value) } }
            .build()
        val call = Http.sseClient.newCall(request)
        onCall(call)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${response.body.string().take(300)}")
            }
            var event = ""
            response.body.charStream().buffered().useLines { lines ->
                lines.forEach { line ->
                    stopped()
                    when {
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            val data = line.removePrefix("data:").trim()
                            when (event) {
                                "complete" -> return parseOutput(data)
                                "error" -> throw IllegalStateException(gradioError(data))
                            }
                        }
                    }
                }
            }
        }
        throw IllegalStateException("Gradio stream closed before completion")
    }

    private fun parseOutput(data: String): JSONArray {
        val value = org.json.JSONTokener(data).nextValue()
        return when (value) {
            is JSONArray -> value
            is JSONObject -> value.optJSONArray("data")
                ?: throw IllegalStateException("Gradio returned an invalid result")
            else -> throw IllegalStateException("Gradio returned an invalid result")
        }
    }

    private fun gradioError(data: String): String = runCatching {
        val value = org.json.JSONTokener(data).nextValue()
        when (value) {
            is JSONObject -> value.optString("message").ifBlank { value.toString() }
            JSONObject.NULL -> "Gradio image generation failed"
            else -> value.toString()
        }
    }.getOrDefault(data).ifBlank { "Gradio image generation failed" }

    private fun readImage(
        provider: ApiProvider,
        output: JSONArray,
        profile: GradioImageProfile,
        stopped: () -> Unit,
        onCall: (okhttp3.Call) -> Unit,
    ): GeneratedImage {
        val first = when (profile) {
            GradioImageProfile.Z_IMAGE_COMMUNITY -> output.opt(0)
            GradioImageProfile.Z_IMAGE_OFFICIAL -> output.optJSONArray(0)?.opt(0)
        }
        val rawUrl = imageUrl(first)
        check(rawUrl.isNotBlank()) { "Gradio returned no image" }
        if (rawUrl.startsWith("data:image/")) return decodeDataImage(rawUrl)

        val base = provider.baseUrl.toHttpUrl()
        val imageUrl = base.resolve(rawUrl) ?: rawUrl.toHttpUrl()
        val request = Request.Builder().url(imageUrl).get().header("Accept", "image/*")
            .apply {
                if (imageUrl.sameOrigin(base)) {
                    authHeaders(provider).forEach { (key, value) -> header(key, value) }
                }
            }
            .build()
        val call = Http.client.newCall(request)
        onCall(call)
        call.execute().use { response ->
            stopped()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: failed to download generated image")
            val body = response.body
            check(body.contentLength() <= MAX_IMAGE_BYTES || body.contentLength() < 0) { "Generated image is too large" }
            val bytes = body.bytes()
            check(bytes.size <= MAX_IMAGE_BYTES) { "Generated image is too large" }
            val mime = body.contentType()?.toString()?.substringBefore(';')
                ?.takeIf { it.startsWith("image/") } ?: inferMime(bytes)
            return GeneratedImage(bytes, mime)
        }
    }

    private fun imageUrl(value: Any?): String {
        when (value) {
            is String -> return value
            is JSONArray -> for (index in 0 until value.length()) {
                val nested = imageUrl(value.opt(index))
                if (nested.isNotBlank()) return nested
            }
            is JSONObject -> {
                val direct = value.optString("url").ifBlank { value.optString("path") }
                if (direct.isNotBlank()) return direct
                return imageUrl(value.opt("image"))
            }
        }
        return ""
    }

    private fun decodeDataImage(value: String): GeneratedImage {
        val header = value.substringBefore(',')
        val mime = header.removePrefix("data:").substringBefore(';').takeIf { it.startsWith("image/") }
            ?: "image/png"
        val bytes = Base64.getDecoder().decode(value.substringAfter(',', ""))
        check(bytes.size <= MAX_IMAGE_BYTES) { "Generated image is too large" }
        return GeneratedImage(bytes, mime)
    }

    private fun requestBody(
        profile: GradioImageProfile,
        prompt: String,
        settings: ImageGenerationSettings,
        seed: Int,
    ): JSONObject {
        val data = when (profile) {
            GradioImageProfile.Z_IMAGE_COMMUNITY -> {
                val (width, height) = imageDimensions(settings)
                JSONArray().put(prompt).put(height).put(width)
                    .put(settings.steps.coerceIn(1, 50)).put(seed).put(false)
            }
            GradioImageProfile.Z_IMAGE_OFFICIAL -> {
                // 官方端点会在收到的 steps 上加 1；项目设置保存的是实际 inference steps。
                val officialSteps = (settings.steps - 1).coerceIn(1, 99)
                JSONArray().put(prompt).put(officialResolution(settings)).put(seed)
                    .put(officialSteps).put(3.0).put(false).put(JSONArray())
            }
        }
        return JSONObject().put("data", data)
    }

    private fun apiName(provider: ApiProvider): String = provider.apiPath.trim()
        .ifBlank {
            when (provider.gradioImageProfile) {
                GradioImageProfile.Z_IMAGE_COMMUNITY -> DEFAULT_COMMUNITY_API_NAME
                GradioImageProfile.Z_IMAGE_OFFICIAL -> DEFAULT_OFFICIAL_API_NAME
            }
        }
        .trim('/')
        .also {
            require(it.isNotBlank() && ".." !in it && '?' !in it && '#' !in it) {
                "Invalid Gradio API endpoint"
            }
        }

    private fun requestHeaders(provider: ApiProvider, model: ModelInfo): Map<String, String> =
        model.applyHeaders(authHeaders(provider))

    private fun authHeaders(provider: ApiProvider): Map<String, String> =
        if (provider.apiKey.isBlank()) emptyMap()
        else mapOf("Authorization" to "Bearer ${provider.apiKey}")

    private fun imageDimensions(settings: ImageGenerationSettings): Pair<Int, Int> {
        val ratio = requestedAspectRatio(settings.aspectRatio)
        // Space 的公开界面上限为 2048；高分辨率档在此范围内渐进放大，避免 4K 直接 OOM。
        val edge = when (settings.resolution.lowercase()) {
            "2k" -> 1536
            "4k" -> 2048
            else -> 1024
        }
        val area = edge.toDouble() * edge
        val width = align32(sqrt(area * ratio).roundToInt())
        val height = align32(sqrt(area / ratio).roundToInt())
        return width to height
    }

    private fun officialResolution(settings: ImageGenerationSettings): String {
        val candidates = officialResolutions[settings.resolution.lowercase()]
            ?: requireNotNull(officialResolutions["1k"])
        val targetRatio = requestedAspectRatio(settings.aspectRatio)
        return requireNotNull(candidates.minByOrNull { abs(ln(it.aspectRatio / targetRatio)) }).value
    }

    private fun requestedAspectRatio(value: String): Double {
        val parts = value.split(':')
        if (parts.size != 2) return 1.0
        val width = parts[0].toDoubleOrNull()
        val height = parts[1].toDoubleOrNull()
        return if (width != null && height != null && width > 0 && height > 0) width / height else 1.0
    }

    private fun align32(value: Int): Int = ((value.coerceIn(256, 2048) + 16) / 32) * 32

    private fun normalizeSeed(seed: Int): Int = (seed.toLong() and Int.MAX_VALUE.toLong()).toInt()

    private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    private fun inferMime(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "image/webp"
        else -> "image/png"
    }
}
