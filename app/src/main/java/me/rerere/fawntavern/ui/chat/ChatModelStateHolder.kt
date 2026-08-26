package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiConfigRepository
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.api.ReasoningLevel

/** Owns the selected model and model-specific generation settings for chat. */
internal class ChatModelStateHolder(
    private val controller: ChatModelController,
    private val repository: ApiConfigRepository,
) {
    var apiConfig by mutableStateOf(repository.load())
        private set

    var reasoning by mutableStateOf(controller.reasoning(apiConfig.currentModel))
        private set

    var imageGeneration by mutableStateOf(controller.imageGeneration(apiConfig.currentModel))
        private set

    var revision by mutableIntStateOf(0)
        private set

    fun effectiveModelSpec(characterName: String?): String? =
        controller.effectiveModelSpec(characterName, apiConfig)

    fun resolveProvider(characterName: String?) =
        controller.resolveProvider(characterName, apiConfig)

    fun reload(characterName: String?) {
        apiConfig = repository.load()
        val spec = effectiveModelSpec(characterName) ?: apiConfig.currentModel
        reasoning = controller.reasoning(spec)
        imageGeneration = controller.imageGeneration(spec)
    }

    fun select(characterName: String?, providerId: String, modelId: String) {
        val spec = "$providerId::$modelId"
        reasoning = controller.select(characterName, spec)
        imageGeneration = controller.imageGeneration(spec)
        revision++
    }

    fun updateReasoning(characterName: String?, level: ReasoningLevel) {
        reasoning = level
        controller.saveReasoning(effectiveModelSpec(characterName) ?: apiConfig.currentModel, level)
    }

    fun updateImageGeneration(characterName: String?, settings: ImageGenerationSettings) {
        imageGeneration = settings
        controller.saveImageGeneration(effectiveModelSpec(characterName) ?: apiConfig.currentModel, settings)
    }

    fun updateApiConfig(config: ApiConfig) {
        apiConfig = config
        repository.save(config)
        revision++
    }

    fun refreshCharacter(characterName: String?) {
        val spec = effectiveModelSpec(characterName).orEmpty()
        reasoning = controller.reasoning(spec)
        imageGeneration = controller.imageGeneration(spec)
    }
}
