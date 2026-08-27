package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiConfigRepository
import me.rerere.fawntavern.data.api.BuiltInTool
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.api.Modality
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.api.supportsBuiltInTool

data class ChatModelCapabilities(
    val builtInSearchAvailable: Boolean,
    val builtInSearchEnabled: Boolean,
    val imageGenerationAvailable: Boolean,
)

/** 持有聊天模型选择及其专属生成设置。 */
class ChatModelStateHolder(
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

    fun capabilities(characterName: String?): ChatModelCapabilities {
        revision
        val (provider, modelId) = resolveProvider(characterName)
            ?: return ChatModelCapabilities(false, false, false)
        val selected = provider.model(modelId)
            ?: return ChatModelCapabilities(false, false, false)
        return ChatModelCapabilities(
            builtInSearchAvailable = selected.supportsBuiltInTool(BuiltInTool.SEARCH, provider),
            builtInSearchEnabled = BuiltInTool.SEARCH in selected.tools,
            imageGenerationAvailable = Modality.IMAGE in selected.outputModalities,
        )
    }

    fun toggleBuiltInSearch(characterName: String?) {
        val (provider, modelId) = resolveProvider(characterName) ?: return
        val modelIndex = provider.models.indexOfFirst { it.id == modelId }
        if (modelIndex < 0) return
        val selected = provider.models[modelIndex]
        if (!selected.supportsBuiltInTool(BuiltInTool.SEARCH, provider)) return
        val updated = selected.copy(tools = if (BuiltInTool.SEARCH in selected.tools) {
            selected.tools - BuiltInTool.SEARCH
        } else {
            selected.tools + BuiltInTool.SEARCH
        })
        val updatedProvider = provider.copy(
            models = provider.models.toMutableList().also { it[modelIndex] = updated },
        )
        updateApiConfig(
            apiConfig.copy(providers = apiConfig.providers.map {
                if (it.id == provider.id) updatedProvider else it
            }),
        )
    }

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
