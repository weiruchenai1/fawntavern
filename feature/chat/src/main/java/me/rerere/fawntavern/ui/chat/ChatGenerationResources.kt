package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.api.GeneratedImage
import me.rerere.fawntavern.data.chat.PersistedGeneratedImage

interface ChatGenerationResources {
    suspend fun persistGeneratedImage(image: GeneratedImage): PersistedGeneratedImage?
    suspend fun saveGlobalVariables(variables: Map<String, String>)
    fun errorText(error: Exception): String
}
