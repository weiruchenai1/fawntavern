package me.rerere.fawntavern.data.api

import java.security.SecureRandom
import java.util.Base64
import kotlin.math.roundToInt
import kotlin.math.sqrt
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Gradio 命名 API 图片适配器；模板约定输入为 prompt/height/width/steps/seed/randomize_seed。 */
internal object GradioImageAdapter : ProviderAdapter {
    private val jsonMediaType = "application/json".toMediaType()
    private const val DEFAULT_API_NAME = "generate_image"
    private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

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
        val (width, height) = imageDimensions(settings)
        val count = settings.count.coerceIn(1, 5)
        val firstSeed = settings.seed?.let(::normalizeSeed) ?: SecureRandom().nextInt(Int.MAX_VALUE)
        val apiName = apiName(provider)
        val submitUrl = "${provider.baseUrl.trimEnd('/')}/gradio_api/call/$apiName"
        val snapshotBody = requestBody(prompt, height, width, settings.steps, firstSeed)
        val snapshot = requestSnapshot(submitUrl, snapshotBody)

        val images = captureRequestFailure(snapshot, stopped) {
            buildList {
                repeat(count) { index ->
                    stopped()
                    val seed = normalizeSeed(firstSeed + index)
                    val body = requestBody(prompt, height, width, settings.steps, seed)
                    val eventId = submit(provider, model, submitUrl, body, stopped, onCall)
                    val output = awaitResult(provider, model, submitUrl, eventId, stopped, onCall)
                    add(readImage(provider, output, stopped, onCall))
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
            else -> value.toString()
        }
    }.getOrDefault(data).ifBlank { "Gradio image generation failed" }

    private fun readImage(
        provider: ApiProvider,
        output: JSONArray,
        stopped: () -> Unit,
        onCall: (okhttp3.Call) -> Unit,
    ): GeneratedImage {
        val first = output.opt(0)
        val rawUrl = when (first) {
            is JSONObject -> first.optString("url").ifBlank { first.optString("path") }
            is String -> first
            else -> ""
        }
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

    private fun decodeDataImage(value: String): GeneratedImage {
        val header = value.substringBefore(',')
        val mime = header.removePrefix("data:").substringBefore(';').takeIf { it.startsWith("image/") }
            ?: "image/png"
        val bytes = Base64.getDecoder().decode(value.substringAfter(',', ""))
        check(bytes.size <= MAX_IMAGE_BYTES) { "Generated image is too large" }
        return GeneratedImage(bytes, mime)
    }

    private fun requestBody(prompt: String, height: Int, width: Int, steps: Int, seed: Int) = JSONObject().put(
        "data",
        JSONArray().put(prompt).put(height).put(width).put(steps.coerceIn(1, 50)).put(seed).put(false),
    )

    private fun apiName(provider: ApiProvider): String = provider.apiPath.trim()
        .ifBlank { DEFAULT_API_NAME }
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
        val parts = settings.aspectRatio.split(':')
        val ratio = if (parts.size == 2) {
            val width = parts[0].toDoubleOrNull()
            val height = parts[1].toDoubleOrNull()
            if (width != null && height != null && width > 0 && height > 0) width / height else 1.0
        } else 1.0
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
