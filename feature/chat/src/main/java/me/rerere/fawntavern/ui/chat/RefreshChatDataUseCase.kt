package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.domain.chat.ChatDataRepository
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.domain.ConversationOps

internal data class RefreshedChatData(
    val summaries: List<ChatSession>,
    val currentSession: ChatSession?,
    val resolvedCard: CharacterCard?,
    val replaceCurrent: Boolean,
    val replaceCard: Boolean,
)

/** 资源或聊天数据删除后重新解析有效的当前会话。 */
internal class RefreshChatDataUseCase(
    private val repository: ChatDataRepository,
    private val promptContext: ChatPromptContextDataSource,
) {
    suspend operator fun invoke(
        currentSession: ChatSession?,
        defaultPresetName: String,
        defaultCharacterName: String,
    ): RefreshedChatData {
        val summaries = repository.listSummaries()
        val current = currentSession
            ?: return RefreshedChatData(summaries, null, null, false, false)
        if (summaries.any { it.id == current.id }) {
            return RefreshedChatData(
                summaries = summaries,
                currentSession = repository.get(current.id),
                resolvedCard = null,
                replaceCurrent = true,
                replaceCard = false,
            )
        }

        val card = if (current.charFile.isBlank()) null else promptContext.loadCard(current.charFile)
        val recentForCharacter = summaries.firstOrNull { it.charFile == current.charFile }
            ?.let { repository.get(it.id) }
        if (recentForCharacter != null) {
            return RefreshedChatData(summaries, recentForCharacter, card, true, true)
        }
        if (card != null) {
            return RefreshedChatData(
                summaries,
                ConversationOps.newSession(card, current.charFile, current.charName),
                card,
                replaceCurrent = true,
                replaceCard = true,
            )
        }

        val defaultName = promptContext.ensureDefaultCharacter(
            defaultPresetName,
            defaultCharacterName,
        )
        val defaultCard = promptContext.loadCard(defaultName)
        return RefreshedChatData(
            summaries,
            ConversationOps.newSession(defaultCard, defaultName, defaultName),
            defaultCard,
            replaceCurrent = true,
            replaceCard = true,
        )
    }
}
