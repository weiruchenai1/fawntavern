package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.preset.StPreset
import me.rerere.fawntavern.data.worldbook.WorldBook

internal data class ChatGenerationSnapshot(
    val card: CharacterCard?,
    val userName: String,
    val worldBooks: List<WorldBook>,
    val preset: StPreset?,
    val promptRegex: List<CharRegex>,
    val reasoning: ReasoningLevel,
    val imageGeneration: ImageGenerationSettings,
    val searchEnabled: Boolean,
    val apiConfig: ApiConfig,
    val chatModel: String?,
)

/** Coordinates generation state updates and the lifecycle that follows a completed response. */
internal class ChatGenerationOrchestrator(
    private val scope: CoroutineScope,
    private val runner: ChatGenerationRunner,
    private val generationState: ChatGenerationCoordinator,
    private val conversation: ChatConversationStateHolder,
    private val sessions: ChatSessionCoordinator,
    private val postGeneration: ChatPostGenerationCoordinator,
    private val snapshot: () -> ChatGenerationSnapshot,
) {
    val isRunning: Boolean
        get() = generationState.isRunning

    val uiState: ChatUiState.GenerationState
        get() = generationState.uiState

    fun launch(block: suspend () -> Unit): Boolean = generationState.launch(block)

    fun launchGeneration(
        sessionId: String,
        provider: ApiProvider,
        modelId: String,
        mode: ChatGenerationMode,
        targetTimestamp: Long?,
    ): Boolean = launch {
        generate(sessionId, provider, modelId, mode, targetTimestamp)
    }

    fun launchRegeneration(
        session: ChatSession,
        provider: ApiProvider,
        modelId: String,
        plan: ChatRegenerationPlan,
    ): Boolean = when (plan) {
        is ChatRegenerationPlan.Regenerate -> launchGeneration(
            session.id,
            provider,
            modelId,
            ChatGenerationMode.REGENERATE,
            plan.targetTimestamp,
        )
        is ChatRegenerationPlan.TruncateAndSend -> launch {
            sessions.truncateAfter(session.id, plan.afterTimestamp)
            generate(
                session.id,
                provider,
                modelId,
                ChatGenerationMode.SEND,
                null,
            )
        }
    }

    fun stop() = generationState.stop()

    suspend fun generate(
        sessionId: String,
        provider: ApiProvider,
        modelId: String,
        mode: ChatGenerationMode,
        targetTimestamp: Long?,
    ) {
        val context = snapshot()
        val result = runner.run(
            request = ChatGenerationRunner.Request(
                sessionId = sessionId,
                provider = provider,
                modelId = modelId,
                mode = mode,
                targetTimestamp = targetTimestamp,
                card = context.card,
                userName = context.userName,
                worldBooks = context.worldBooks,
                preset = context.preset,
                promptRegex = context.promptRegex,
                reasoning = context.reasoning,
                imageGeneration = context.imageGeneration,
                searchEnabled = context.searchEnabled,
            ),
            onStarted = { base, message ->
                conversation.replaceCurrent(base)
                generationState.markTarget(message.ts)
                conversation.putOverlay(message)
            },
            onLocalVariablesCommitted = { variables ->
                conversation.updateCurrent(sessionId) { it.copy(localVariables = variables) }
            },
            onUpdate = conversation::putOverlay,
        ) ?: return

        result.completedSession?.let { completed ->
            if (conversation.current?.id == sessionId) conversation.replaceCurrent(completed)
            runPostGeneration(completed)
        }
    }

    fun generateTitle(session: ChatSession, force: Boolean) {
        val context = snapshot()
        val chatModel = context.chatModel ?: return
        postGeneration.generateTitle(
            session = session,
            force = force,
            chatModel = chatModel,
            apiConfig = context.apiConfig,
            userName = context.userName,
            characterName = context.card?.name ?: session.charName,
            onTitle = { title ->
                conversation.updateCurrent(session.id) { it.copy(title = title) }
            },
        )
    }

    fun generateTitle(sessionId: String) {
        scope.launch {
            sessions.open(sessionId)?.let { generateTitle(it, force = true) }
        }
    }

    private fun runPostGeneration(session: ChatSession) {
        val context = snapshot()
        postGeneration.runExtensions(
            session = session,
            apiConfig = context.apiConfig,
            userName = context.userName,
            characterName = context.card?.name ?: session.charName,
            isCurrent = { conversation.current?.id == session.id },
            onSessionRefreshed = { fresh ->
                conversation.updateCurrent(session.id) { it.copy(extState = fresh.extState) }
            },
        )
        generateTitle(session, force = false)
    }
}
