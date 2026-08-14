package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.data.chat.ChatRepository
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.settings.DefaultModelStore
import me.rerere.fawntavern.extension.HostServices

/** 负责标题模型选择、上下文构造、调用和落盘。 */
internal class ChatTitleGenerator(
    private val context: Context,
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
        val resolved = DefaultModelStore.resolveModel(
            context,
            DefaultModelStore.ROLE_TITLE,
            chatModel,
        ) ?: return null
        val (providerId, modelId) = resolved
        if (apiConfig.providers.none { it.id == providerId && it.enabled }) return null

        val promptEntry = DefaultModelStore.get(context, DefaultModelStore.ROLE_TITLE)
        val promptTemplate = promptEntry.prompt.ifBlank { DefaultModelStore.DEFAULT_TITLE_PROMPT }
        val title = HostServices(context, apiConfig).callModel(
            messages = listOf(ApiMessage("user", promptTemplate.replace("{content}", historyPreview))),
            params = null,
            modelId = "$providerId::$modelId",
        ).trim().take(80)
        if (title.isBlank()) return null

        ChatRepository.updateTitle(context, session.id, title)
        return title
    }
}

internal fun buildTitleHistory(
    session: ChatSession,
    userName: String,
    charName: String,
): String? {
    val userMessages = session.messages.filter { it.role == "user" }
    val assistantMessages = session.messages.filter { it.role == "assistant" }
    if (userMessages.isEmpty() || assistantMessages.isEmpty()) return null
    val pairCount = minOf(userMessages.size, assistantMessages.size, 2)
    return buildList {
        repeat(pairCount) { index ->
            add("$userName: ${userMessages[index].content.take(200)}")
            add("$charName: ${assistantMessages[index].content.take(200)}")
        }
    }.joinToString("\n")
}
