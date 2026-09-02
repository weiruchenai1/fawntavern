package me.rerere.fawntavern.ui.chat

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.data.api.GenParams
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.domain.GenerationEvent
import me.rerere.fawntavern.domain.GenerationGateway
import me.rerere.fawntavern.domain.GenerationStreamRequest
import org.json.JSONArray
import org.json.JSONObject

internal class ChatFrontendGenerationController(
    private val config: () -> ApiConfig,
    private val gateway: GenerationGateway,
    private val emitEvent: (String, String) -> Unit,
) {
    private val cancellations = ConcurrentHashMap<String, AtomicBoolean>()

    fun models(): String = JSONArray().apply {
        config().providers.filter { it.enabled }.forEach { provider ->
            provider.models.forEach { model ->
                put(JSONObject().put("id", "${provider.id}::${model.id}").put("name", model.name.ifBlank { model.id }))
            }
        }
    }.toString()

    suspend fun generate(params: JSONObject): String {
        val spec = params.optString("model").ifBlank { config().currentModel }
        val providerId = spec.substringBefore("::")
        val modelId = spec.substringAfter("::", "")
        val provider = config().providers.firstOrNull { it.id == providerId && it.enabled }
            ?: error("Generation provider is unavailable")
        require(provider.models.any { it.id == modelId }) { "Generation model is unavailable" }

        val messages = parseMessages(params)
        require(messages.isNotEmpty()) { "Generation messages are empty" }
        val generationId = params.optString("generation_id").ifBlank { UUID.randomUUID().toString() }
        val cancelled = AtomicBoolean(false)
        cancellations[generationId] = cancelled
        emitEvent("generation_started", JSONObject().put("generation_id", generationId).toString())
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val requestJob = currentCoroutineContext()[Job]
        try {
            gateway.stream(
                GenerationStreamRequest(
                    providerId = providerId,
                    modelId = modelId,
                    messages = messages,
                    params = parseGenParams(params.optJSONObject("params") ?: params),
                    tools = emptyList(),
                    isCancelled = { cancelled.get() || requestJob?.isActive == false },
                ),
            ) { event ->
                when (event) {
                    is GenerationEvent.ContentDelta -> {
                        content.append(event.content)
                        emitEvent(
                            "stream_token_received",
                            JSONObject().put("generation_id", generationId).put("text", event.content).toString(),
                        )
                    }
                    is GenerationEvent.ReasoningDelta -> reasoning.append(event.reasoning)
                }
            }
        } finally {
            cancellations.remove(generationId)
        }
        val result = JSONObject()
            .put("generation_id", generationId)
            .put("content", content.toString())
            .put("reasoning", reasoning.toString())
            .toString()
        emitEvent("generation_ended", result)
        return result
    }

    fun stop(generationId: String?): String {
        if (generationId.isNullOrBlank()) cancellations.values.forEach { it.set(true) }
        else cancellations[generationId]?.set(true)
        return "null"
    }

    private fun parseMessages(params: JSONObject): List<ApiMessage> {
        val array = params.optJSONArray("messages")
        if (array != null) return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val content = item.optString("content").ifBlank { item.optString("message") }
            content.takeIf { it.isNotBlank() }?.let {
                ApiMessage(item.optString("role", "user").takeIf { role -> role in ALLOWED_ROLES } ?: "user", it)
            }
        }
        return params.optString("prompt").takeIf { it.isNotBlank() }?.let { listOf(ApiMessage("user", it)) }.orEmpty()
    }

    private fun parseGenParams(params: JSONObject): GenParams = GenParams(
        temperature = params.optDoubleOrNull("temperature")?.toFloat(),
        topP = params.optDoubleOrNull("top_p")?.toFloat(),
        topK = params.optIntOrNull("top_k"),
        maxTokens = params.optIntOrNull("max_tokens"),
        frequencyPenalty = params.optDoubleOrNull("frequency_penalty")?.toFloat(),
        presencePenalty = params.optDoubleOrNull("presence_penalty")?.toFloat(),
        seed = params.optIntOrNull("seed"),
        reasoning = ReasoningLevel.fromName(params.optString("reasoning").uppercase()),
    )

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private companion object {
        val ALLOWED_ROLES = setOf("system", "user", "assistant")
    }
}
