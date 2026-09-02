package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.api.ReasoningLevel

data class ChatUiState(
    val conversation: ChatConversationState,
    val input: ChatInputState,
    val generation: ChatGenerationState,
    val profile: ChatProfileState,
    val model: ModelState,
    val search: ChatSearchState,
    val settings: ChatUiSettings,
    val globalVariables: Map<String, String> = emptyMap(),
) {
    data class ModelState(
        val apiConfig: ApiConfig,
        val revision: Int,
        val displaySpec: String?,
        val reasoning: ReasoningLevel,
        val imageGeneration: ImageGenerationSettings,
        val imageGenerationAvailable: Boolean,
    )
}
