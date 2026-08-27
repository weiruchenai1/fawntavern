package me.rerere.fawntavern.data.generation

import me.rerere.fawntavern.data.api.ApiConfigRepository
import me.rerere.fawntavern.data.api.ApiRequestException
import me.rerere.fawntavern.data.api.ChatApi
import me.rerere.fawntavern.domain.GenerationCancelled
import me.rerere.fawntavern.domain.GenerationEvent
import me.rerere.fawntavern.domain.GenerationGateway
import me.rerere.fawntavern.domain.GenerationRequestException
import me.rerere.fawntavern.domain.GenerationStreamRequest

/** Maps the provider-neutral generation contract to the existing network adapters. */
class NetworkGenerationGateway(
    private val apiConfigRepository: ApiConfigRepository,
) : GenerationGateway {
    override suspend fun stream(
        request: GenerationStreamRequest,
        onEvent: (GenerationEvent) -> Unit,
    ) = try {
        val provider = apiConfigRepository.load().providers
            .find { it.id == request.providerId }
            ?: throw IllegalStateException("Generation provider not found: ${request.providerId}")
        ChatApi.streamChat(
            provider = provider,
            modelId = request.modelId,
            messages = request.messages,
            params = request.params,
            tools = request.tools,
            isCancelled = request.isCancelled,
            onDelta = { content, reasoning ->
                if (content.isNotEmpty()) onEvent(GenerationEvent.ContentDelta(content))
                if (reasoning.isNotEmpty()) onEvent(GenerationEvent.ReasoningDelta(reasoning))
            },
        )
    } catch (_: ChatApi.Stopped) {
        throw GenerationCancelled()
    } catch (error: ApiRequestException) {
        throw GenerationRequestException(
            snapshot = error.snapshot,
            cause = error.cause as? Exception ?: error,
        )
    }
}
