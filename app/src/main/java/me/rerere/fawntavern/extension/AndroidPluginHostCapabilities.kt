package me.rerere.fawntavern.extension

import me.rerere.fawntavern.domain.chat.ChatDataRepository

internal class AndroidPluginHostCapabilities(
    private val chatRepository: ChatDataRepository,
) : PluginHostCapabilities {
    override suspend fun savePluginState(sessionId: String, pluginId: String, state: String) {
        val latest = chatRepository.get(sessionId) ?: error("会话不存在")
        val next = latest.extState.toMutableMap()
        if (state.isBlank()) next.remove(pluginId) else next[pluginId] = state
        chatRepository.save(latest.copy(extState = next, updatedAt = System.currentTimeMillis()))
    }
}
