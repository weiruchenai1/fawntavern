package me.rerere.fawntavern.ui.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.core.diagnostics.SafeLog
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.BuiltInTool
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.api.ToolChoice
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.AttachmentStore
import me.rerere.fawntavern.data.chat.ChatRepository
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.preset.StPreset
import me.rerere.fawntavern.data.settings.GlobalVariableStore
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.domain.GenerationController

internal class ChatGenerationRunner(
    private val context: Context,
    private val generation: GenerationController,
    private val promptAssembler: ChatPromptAssembler = ChatPromptAssembler(context),
    private val searchTool: ChatSearchTool = ChatSearchTool(context),
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
        val toolChoice: ToolChoice,
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
        val base = ChatRepository.get(context, request.sessionId) ?: return null
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
        onStarted(base, generationMessage)

        val builtInSearch = request.provider.model(request.modelId)
            ?.tools
            ?.contains(BuiltInTool.SEARCH) == true
        val useSearchTool = request.searchEnabled && !builtInSearch
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
            )
        )
        val built = assembled.built.copy(
            genParams = assembled.built.genParams?.copy(
                imageGeneration = request.imageGeneration,
                toolChoice = request.toolChoice,
            ) ?: me.rerere.fawntavern.data.api.GenParams(
                imageGeneration = request.imageGeneration,
                toolChoice = request.toolChoice,
            ),
        )
        val variableState = assembled.variableState
        val localChanged = commitVariables && variableState.localChanged()
        val globalChanged = commitVariables && variableState.globalChanged()
        var localCommitted = false
        var globalCommitted = false

        try {
            if (localChanged) {
                ChatRepository.saveLocalVariables(
                    context,
                    request.sessionId,
                    variableState.localVariables(),
                )
                localCommitted = true
                onLocalVariablesCommitted(variableState.localVariables())
            }
            if (globalChanged) {
                withContext(Dispatchers.IO) {
                    GlobalVariableStore.set(context, variableState.globalVariables())
                }
                globalCommitted = true
            }
            val finalMessage = generation.run(
                apiMessages = assembled.apiMessages,
                genMessage = generationMessage,
                provider = request.provider,
                modelId = request.modelId,
                built = built,
                streaming = request.card?.streaming ?: true,
                tools = if (useSearchTool) listOf(searchTool.spec()) else emptyList(),
                toolExecutor = if (useSearchTool) searchTool.executor() else null,
                persistGeneratedImage = { image ->
                    AttachmentStore.persistGeneratedImage(context, image)
                },
                errorText = { error ->
                    context.getString(R.string.chat_error_fmt, error.message.orEmpty())
                },
                onUpdate = onUpdate,
            )
            ChatRepository.commitGeneration(
                context,
                request.sessionId,
                finalMessage,
                assembled.built.timedWi,
            )
            onUpdate(finalMessage)
            return Result(
                completedSession = ChatRepository.get(context, request.sessionId),
                finalMessage = finalMessage,
            )
        } catch (error: Exception) {
            if (localCommitted) {
                runCatching {
                    ChatRepository.saveLocalVariables(context, request.sessionId, base.localVariables)
                }.onFailure { rollbackError ->
                    error.addSuppressed(rollbackError)
                    SafeLog.warn(TAG, "session_variables_rollback_failed", rollbackError)
                }
            }
            if (globalCommitted) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        GlobalVariableStore.set(context, variableState.initialGlobalVariables())
                    }.onFailure { rollbackError ->
                        error.addSuppressed(rollbackError)
                        SafeLog.warn(TAG, "global_variables_rollback_failed", rollbackError)
                    }
                }
            }
            throw error
        }
    }

    private companion object {
        const val TAG = "ChatGenerationRunner"
    }
}
