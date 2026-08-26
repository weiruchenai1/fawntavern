package me.rerere.fawntavern.di

import android.content.Context
import me.rerere.fawntavern.data.api.ChatApi
import me.rerere.fawntavern.data.api.ApiRequestException
import me.rerere.fawntavern.data.api.ApiConfigRepository
import me.rerere.fawntavern.data.api.PreferencesApiConfigRepository
import me.rerere.fawntavern.data.chat.ChatDataRepository
import me.rerere.fawntavern.data.chat.RoomChatDataRepository
import me.rerere.fawntavern.domain.GenerationEvent
import me.rerere.fawntavern.domain.GenerationCancelled
import me.rerere.fawntavern.domain.GenerationGateway
import me.rerere.fawntavern.domain.GenerationRequestException
import me.rerere.fawntavern.domain.GenerationStreamRequest
import me.rerere.fawntavern.extension.AndroidExtensionGateway
import me.rerere.fawntavern.extension.ExtensionGateway

/** 应用级依赖装配点，避免 ViewModel 和业务对象自行获取全局存储或网络实现。 */
internal class AppContainer(context: Context) {
    val chatRepository: ChatDataRepository = RoomChatDataRepository(context)
    val apiConfigRepository: ApiConfigRepository = PreferencesApiConfigRepository(context)
    val generationGateway: GenerationGateway = AndroidGenerationGateway
    val extensionGateway: ExtensionGateway = AndroidExtensionGateway(context)
}

private object AndroidGenerationGateway : GenerationGateway {
    override suspend fun stream(
        request: GenerationStreamRequest,
        onEvent: (GenerationEvent) -> Unit,
    ) = try {
        ChatApi.streamChat(
            provider = request.provider,
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
