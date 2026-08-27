package me.rerere.fawntavern.domain

import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.data.api.ApiRequestSnapshot
import me.rerere.fawntavern.data.api.ApiToolCall
import me.rerere.fawntavern.data.api.GenParams
import me.rerere.fawntavern.data.api.StreamEnd
import me.rerere.fawntavern.data.api.ToolSpec
import me.rerere.fawntavern.data.chat.MsgSearch

internal data class GenerationStreamRequest(
    val providerId: String,
    val modelId: String,
    val messages: List<ApiMessage>,
    val params: GenParams?,
    val tools: List<ToolSpec>,
    val isCancelled: () -> Boolean,
)

internal sealed interface GenerationEvent {
    data class ContentDelta(val content: String) : GenerationEvent
    data class ReasoningDelta(val reasoning: String) : GenerationEvent
}

internal class GenerationCancelled : Exception()

internal class GenerationRequestException(
    val snapshot: ApiRequestSnapshot,
    cause: Exception,
) : Exception(cause.message, cause)

internal fun interface GenerationGateway {
    suspend fun stream(
        request: GenerationStreamRequest,
        onEvent: (GenerationEvent) -> Unit,
    ): StreamEnd
}

internal interface GenerationToolExecutor {
    fun describe(call: ApiToolCall): MsgSearch?
    suspend fun execute(call: ApiToolCall): Pair<String, MsgSearch?>
}
