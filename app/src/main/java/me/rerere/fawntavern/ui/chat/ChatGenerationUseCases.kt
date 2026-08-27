package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.api.BuiltInTool
import me.rerere.fawntavern.data.api.GenParams
import me.rerere.fawntavern.domain.chat.ChatDataRepository
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.domain.ChatGenerationMode
import me.rerere.fawntavern.domain.ChatGenerationPlanner

internal data class PreparedChatGeneration(
    val baseSession: ChatSession,
    val generationMessage: ChatMessage,
    val prompt: ChatPromptAssembler.Result,
    val useSearchTool: Boolean,
    val commitVariables: Boolean,
)

/** 从权威会话快照生成本次请求计划，不执行网络或持久化。 */
internal class PrepareChatGenerationUseCase(
    private val repository: ChatDataRepository,
    private val promptAssembler: ChatPromptAssembler,
) {
    suspend operator fun invoke(request: ChatGenerationRunner.Request): PreparedChatGeneration? {
        val base = repository.get(request.sessionId) ?: return null
        val plan = ChatGenerationPlanner.create(
            base,
            request.modelId,
            request.mode,
            request.targetTimestamp,
        ) ?: return null
        val generationMessage = if (request.mode == ChatGenerationMode.REGENERATE) {
            plan.message.copy(searches = emptyList())
        } else {
            plan.message
        }.copy(imageAspectRatio = request.imageGeneration.aspectRatio)
        val commitVariables = request.mode == ChatGenerationMode.SEND
        val assembled = promptAssembler.assemble(
            ChatPromptAssembler.Request(
                session = base,
                card = request.card,
                userName = request.userName,
                worldBooks = request.worldBooks,
                preset = request.preset,
                promptRegex = request.promptRegex,
                buildHistory = plan.buildHistory,
                promptHistory = plan.promptHistory,
                trimSummarizedHistory = commitVariables,
                updateTimed = plan.updateTimedWorldInfo,
                reasoning = request.reasoning,
                modelId = request.modelId,
                generationMessage = plan.message,
                commitVariables = commitVariables,
            ),
        )
        val built = assembled.built.copy(
            genParams = assembled.built.genParams?.copy(
                imageGeneration = request.imageGeneration,
            ) ?: GenParams(imageGeneration = request.imageGeneration),
        )
        return PreparedChatGeneration(
            baseSession = base,
            generationMessage = generationMessage,
            prompt = assembled.copy(built = built),
            useSearchTool = request.searchEnabled &&
                request.provider.model(request.modelId)?.tools?.contains(BuiltInTool.SEARCH) != true,
            commitVariables = commitVariables,
        )
    }
}
