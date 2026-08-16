package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.settings.CharacterModelStore
import me.rerere.fawntavern.data.settings.DefaultModelStore
import me.rerere.fawntavern.data.settings.ImageGenerationStore
import me.rerere.fawntavern.data.settings.ThinkingStore

internal interface ChatModelDataSource {
    fun characterModel(characterName: String): String
    fun saveCharacterModel(characterName: String, modelSpec: String)
    fun defaultChatModel(): String
    fun saveDefaultChatModel(modelSpec: String)
    fun reasoning(modelSpec: String): ReasoningLevel
    fun saveReasoning(modelSpec: String, level: ReasoningLevel)
    fun imageGeneration(modelSpec: String): ImageGenerationSettings
    fun saveImageGeneration(modelSpec: String, settings: ImageGenerationSettings)
}

internal class AndroidChatModelDataSource(
    private val context: Context,
) : ChatModelDataSource {
    override fun characterModel(characterName: String): String = CharacterModelStore.get(context, characterName)
    override fun saveCharacterModel(characterName: String, modelSpec: String) =
        CharacterModelStore.set(context, characterName, modelSpec)
    override fun defaultChatModel(): String =
        DefaultModelStore.get(context, DefaultModelStore.ROLE_CHAT).model
    override fun saveDefaultChatModel(modelSpec: String) =
        DefaultModelStore.setModel(context, DefaultModelStore.ROLE_CHAT, modelSpec)
    override fun reasoning(modelSpec: String): ReasoningLevel = ThinkingStore.get(context, modelSpec)
    override fun saveReasoning(modelSpec: String, level: ReasoningLevel) = ThinkingStore.set(context, modelSpec, level)
    override fun imageGeneration(modelSpec: String): ImageGenerationSettings = ImageGenerationStore.get(context, modelSpec)
    override fun saveImageGeneration(modelSpec: String, settings: ImageGenerationSettings) =
        ImageGenerationStore.set(context, modelSpec, settings)
}

internal class ChatModelController(
    private val dataSource: ChatModelDataSource,
) {
    fun effectiveModelSpec(characterName: String?, apiConfig: ApiConfig): String? {
        val characterModel = characterName
            ?.takeIf { it.isNotBlank() }
            ?.let(dataSource::characterModel)
            ?.takeIf { it.isNotBlank() }
        val defaultModel = dataSource.defaultChatModel().takeIf { it.isNotBlank() }
        return listOfNotNull(characterModel, defaultModel, apiConfig.currentModel.takeIf { it.isNotBlank() })
            .firstOrNull { isAvailable(it, apiConfig) }
    }

    fun select(characterName: String?, modelSpec: String): ReasoningLevel {
        if (characterName.isNullOrBlank()) dataSource.saveDefaultChatModel(modelSpec)
        else dataSource.saveCharacterModel(characterName, modelSpec)
        return dataSource.reasoning(modelSpec)
    }

    fun reasoning(modelSpec: String): ReasoningLevel = dataSource.reasoning(modelSpec)

    fun saveReasoning(modelSpec: String, level: ReasoningLevel) {
        dataSource.saveReasoning(modelSpec, level)
    }

    fun imageGeneration(modelSpec: String): ImageGenerationSettings = dataSource.imageGeneration(modelSpec)

    fun saveImageGeneration(modelSpec: String, settings: ImageGenerationSettings) {
        dataSource.saveImageGeneration(modelSpec, settings)
    }

    fun resolveProvider(
        characterName: String?,
        apiConfig: ApiConfig,
    ): Pair<ApiProvider, String>? {
        val spec = effectiveModelSpec(characterName, apiConfig) ?: return null
        val providerId = spec.substringBefore("::")
        val modelId = spec.substringAfter("::", "")
        val provider = apiConfig.providers.find { it.id == providerId && it.enabled }
        return if (provider?.model(modelId) != null) provider to modelId else null
    }

    private fun isAvailable(modelSpec: String, apiConfig: ApiConfig): Boolean {
        val providerId = modelSpec.substringBefore("::")
        val modelId = modelSpec.substringAfter("::", "")
        return modelId.isNotBlank() && apiConfig.providers.any {
            it.id == providerId && it.enabled && it.model(modelId) != null
        }
    }
}
