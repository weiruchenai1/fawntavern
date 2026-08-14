package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.preset.StPreset
import me.rerere.fawntavern.data.settings.GlobalVariableStore
import me.rerere.fawntavern.data.settings.UserProfileStore
import me.rerere.fawntavern.data.settings.WorldInfoSettingsStore
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.domain.MacroVariableState
import me.rerere.fawntavern.domain.PromptBuilder
import me.rerere.fawntavern.extension.ExtensionStore
import me.rerere.fawntavern.extension.PromptContext
import me.rerere.fawntavern.extension.PromptContributor
import org.json.JSONObject

/** 生成前的静态上下文组装；持久化变量提交仍由 ViewModel 负责。 */
internal class ChatPromptAssembler(
    private val context: Context,
) {
    data class Request(
        val session: ChatSession,
        val card: CharacterCard?,
        val userName: String,
        val worldBooks: List<WorldBook>,
        val preset: StPreset?,
        val promptRegex: List<CharRegex>,
        val buildHistory: List<ChatMessage>,
        val promptHistory: List<ChatMessage>,
        val trimSummarizedHistory: Boolean,
        val updateTimed: Boolean,
        val reasoning: ReasoningLevel,
        val modelId: String,
        val generationMessage: ChatMessage,
        val commitVariables: Boolean,
    )

    data class Result(
        val built: PromptBuilder.Built,
        val apiMessages: List<ApiMessage>,
        val variableState: MacroVariableState,
    )

    fun assemble(request: Request): Result {
        val extraPre = mutableListOf<PromptBuilder.Piece>()
        val extraPost = mutableListOf<PromptBuilder.Piece>()
        val extraDepth = mutableListOf<PromptBuilder.DepthPiece>()
        var historySkip = 0
        val enabledExtensions = ExtensionStore.enabledExtensions(context)
        for (extension in enabledExtensions) {
            if (extension !is PromptContributor) continue
            val contribution = extension.contribute(
                PromptContext(
                    session = request.session,
                    charName = request.card?.name ?: request.session.charName,
                    userName = request.userName,
                    extState = request.session.extState[extension.info.id] ?: "",
                    config = ExtensionStore.getConfig(context, extension.info.id),
                ),
            )
            val source = PromptBuilder.PromptSource.EXTENSION
            extraPre += contribution.preHistory.map {
                PromptBuilder.Piece(it.role, it.content, source, extension.info.name)
            }
            extraPost += contribution.postHistory.map {
                PromptBuilder.Piece(it.role, it.content, source, extension.info.name)
            }
            extraDepth += contribution.depthInjections.map {
                PromptBuilder.DepthPiece(it.role, it.content, it.depth, source, extension.info.name)
            }
            historySkip = maxOf(historySkip, contribution.skipMessagesUpTo)
        }

        val buildHistory = request.buildHistory.trimHistory(historySkip, request.trimSummarizedHistory)
        val promptHistory = request.promptHistory.trimHistory(historySkip, request.trimSummarizedHistory)
        val variableState = MacroVariableState(
            localVariables = request.session.localVariables,
            globalVariables = GlobalVariableStore.get(context),
        )
        val built = PromptBuilder.build(
            card = request.card,
            userName = request.userName,
            userDescription = UserProfileStore.getDescription(context),
            worldBooks = request.worldBooks,
            preset = request.preset,
            history = buildHistory,
            promptRegex = request.promptRegex,
            timedWi = request.session.timedWi,
            updateTimed = request.updateTimed,
            wiSettings = WorldInfoSettingsStore.get(context),
            reasoning = request.reasoning,
            extraPre = extraPre,
            extraPost = extraPost,
            extraDepth = extraDepth,
            sessionId = request.session.id,
            input = request.session.messages.lastOrNull { it.role == "user" }?.content.orEmpty(),
            model = request.modelId,
            summary = runCatching {
                JSONObject(request.session.extState["builtin.summarize"].orEmpty()).optString("summary")
            }.getOrDefault(""),
            enabledExtensions = enabledExtensions.flatMap { listOf(it.info.id, it.info.name) }.toSet(),
            pickSalt = request.generationMessage.alts.size,
            variableState = variableState,
            allowVariableMutations = request.commitVariables,
        )
        return Result(
            built = built,
            apiMessages = PromptBuilder.assemble(
                built = built,
                history = promptHistory,
                baseDir = context.filesDir,
                mutateLastUserMessage = request.commitVariables,
            ),
            variableState = variableState,
        )
    }

    private fun List<ChatMessage>.trimHistory(skip: Int, enabled: Boolean): List<ChatMessage> =
        if (enabled && skip > 0 && skip < size) drop(skip) else this
}
