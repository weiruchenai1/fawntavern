package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.domain.chat.ChatDataRepository
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.extension.ExtensionGateway
import me.rerere.fawntavern.domain.chat.buildTitleHistory

/** 负责标题模型选择、上下文构造、调用和落盘。 */
interface ChatTitleSettingsDataSource {
    fun resolveModel(chatModel: String): Pair<String, String>?
    fun promptTemplate(): String
}

/** 负责标题模型选择、上下文构造、调用和落盘。 */
class ChatTitleGenerator(
    private val chatRepository: ChatDataRepository,
    private val extensions: ExtensionGateway,
    private val settings: ChatTitleSettingsDataSource,
) {
    suspend fun generate(
        session: ChatSession,
        force: Boolean,
        chatModel: String,
        apiConfig: ApiConfig,
        userName: String,
        charName: String,
    ): String? {
        if (!force && session.title.isNotBlank()) return null
        val historyPreview = buildTitleHistory(session, userName, charName) ?: return null
        val resolved = settings.resolveModel(chatModel) ?: return null
        val (providerId, modelId) = resolved
        if (apiConfig.providers.none { it.id == providerId && it.enabled }) return null

        val promptTemplate = settings.promptTemplate()
        val title = extensions.services(apiConfig).callModel(
            messages = listOf(ApiMessage("user", promptTemplate.replace("{content}", historyPreview))),
            params = null,
            modelId = "$providerId::$modelId",
        ).trim().take(80)
        if (title.isBlank()) return null

        chatRepository.updateTitle(session.id, title)
        return title
    }
}
