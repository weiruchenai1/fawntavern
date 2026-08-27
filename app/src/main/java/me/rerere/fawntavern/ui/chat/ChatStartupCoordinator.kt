package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.chat.ChatSession

/** Initializes the default character and resolves the first active conversation. */
internal class ChatStartupCoordinator(
    private val sessions: ChatSessionCoordinator,
    private val promptContext: ChatPromptContextDataSource,
) {
    suspend fun observe(
        defaultPresetName: String,
        defaultCharacterName: String,
        newChatOnLaunch: () -> Boolean,
        currentSession: () -> ChatSession?,
        onSessions: (List<ChatSession>) -> Unit,
        onSessionSelected: (ChatSession?) -> Unit,
    ) {
        val defaultName = promptContext.ensureDefaultCharacter(
            defaultPresetName,
            defaultCharacterName,
        )
        if (sessions.count() == 0) {
            onSessionSelected(createDefaultSession(defaultName))
        }
        sessions.observeSessions().collect { summaries ->
            onSessions(summaries)
            if (currentSession() != null) return@collect
            val selected = if (newChatOnLaunch()) {
                createDefaultSession(defaultName)
            } else {
                summaries.firstOrNull()?.let { sessions.open(it.id) }
            }
            onSessionSelected(selected)
        }
    }

    private suspend fun createDefaultSession(defaultName: String): ChatSession =
        sessions.create(
            card = promptContext.loadCard(defaultName),
            charFile = defaultName,
            charName = defaultName,
            persist = false,
        )
}
