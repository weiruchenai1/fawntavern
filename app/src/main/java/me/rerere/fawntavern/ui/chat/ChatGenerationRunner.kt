package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.core.diagnostics.SafeLog
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatDataRepository
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.preset.StPreset
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.domain.GenerationController

internal class ChatGenerationRunner(
    private val chatRepository: ChatDataRepository,
    private val generation: GenerationController,
    private val resources: ChatGenerationResources,
    private val prepare: PrepareChatGenerationUseCase,
    private val commit: CommitChatGenerationUseCase,
    private val searchTool: ChatSearchTool,
) {
    data class Request(
        val sessionId: String,
        val provider: ApiProvider,
        val modelId: String,
        val mode: ChatGenerationMode,
        val targetTimestamp: Long?,
        val card: CharacterCard?,
        val userName: String,
        val worldBooks: List<WorldBook>,
        val preset: StPreset?,
        val promptRegex: List<CharRegex>,
        val reasoning: ReasoningLevel,
        val imageGeneration: ImageGenerationSettings,
        val searchEnabled: Boolean,
    )

    data class Result(
        val completedSession: ChatSession?,
        val finalMessage: ChatMessage,
    )

    suspend fun run(
        request: Request,
        onStarted: (ChatSession, ChatMessage) -> Unit,
        onLocalVariablesCommitted: (Map<String, String>) -> Unit,
        onUpdate: (ChatMessage) -> Unit,
    ): Result? {
        val prepared = prepare(request) ?: return null
        val base = prepared.baseSession
        val generationMessage = prepared.generationMessage
        onStarted(base, generationMessage)
        val assembled = prepared.prompt
        val built = assembled.built
        val variableState = assembled.variableState
        val localChanged = prepared.commitVariables && variableState.localChanged()
        val globalChanged = prepared.commitVariables && variableState.globalChanged()
        var localCommitted = false
        var globalCommitted = false

        try {
            if (localChanged) {
                chatRepository.saveLocalVariables(
                    request.sessionId,
                    variableState.localVariables(),
                )
                localCommitted = true
                onLocalVariablesCommitted(variableState.localVariables())
            }
            if (globalChanged) {
                resources.saveGlobalVariables(variableState.globalVariables())
                globalCommitted = true
            }
            val finalMessage = generation.run(
                apiMessages = assembled.apiMessages,
                genMessage = generationMessage,
                provider = request.provider,
                modelId = request.modelId,
                built = built,
                streaming = request.card?.streaming ?: true,
                tools = if (prepared.useSearchTool) listOf(searchTool.spec()) else emptyList(),
                toolExecutor = if (prepared.useSearchTool) searchTool.executor() else null,
                persistGeneratedImage = resources::persistGeneratedImage,
                errorText = resources::errorText,
                onUpdate = onUpdate,
            )
            val completedSession = commit(request.sessionId, finalMessage, assembled.built.timedWi)
            onUpdate(finalMessage)
            return Result(
                completedSession = completedSession,
                finalMessage = finalMessage,
            )
        } catch (error: Exception) {
            if (localCommitted) {
                runCatching {
                    chatRepository.saveLocalVariables(request.sessionId, base.localVariables)
                }.onFailure { rollbackError ->
                    error.addSuppressed(rollbackError)
                    SafeLog.warn(TAG, "session_variables_rollback_failed", rollbackError)
                }
            }
            if (globalCommitted) {
                runCatching {
                    resources.saveGlobalVariables(variableState.initialGlobalVariables())
                }.onFailure { rollbackError ->
                    error.addSuppressed(rollbackError)
                    SafeLog.warn(TAG, "global_variables_rollback_failed", rollbackError)
                }
            }
            throw error
        }
    }

    private companion object {
        const val TAG = "ChatGenerationRunner"
    }
}
