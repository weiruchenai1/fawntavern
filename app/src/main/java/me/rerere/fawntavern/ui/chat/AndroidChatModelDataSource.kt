package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.settings.CharacterModelStore
import me.rerere.fawntavern.data.settings.DefaultModelStore
import me.rerere.fawntavern.data.settings.ImageGenerationStore
import me.rerere.fawntavern.data.settings.ThinkingStore

internal class AndroidChatModelDataSource(
    private val context: Context,
) : ChatModelDataSource {
    override fun characterModel(characterName: String): String =
        CharacterModelStore.get(context, characterName)

    override fun saveCharacterModel(characterName: String, modelSpec: String) =
        CharacterModelStore.set(context, characterName, modelSpec)

    override fun defaultChatModel(): String =
        DefaultModelStore.get(context, DefaultModelStore.ROLE_CHAT).model

    override fun saveDefaultChatModel(modelSpec: String) =
        DefaultModelStore.setModel(context, DefaultModelStore.ROLE_CHAT, modelSpec)

    override fun reasoning(modelSpec: String): ReasoningLevel = ThinkingStore.get(context, modelSpec)

    override fun saveReasoning(modelSpec: String, level: ReasoningLevel) =
        ThinkingStore.set(context, modelSpec, level)

    override fun imageGeneration(modelSpec: String): ImageGenerationSettings =
        ImageGenerationStore.get(context, modelSpec)

    override fun saveImageGeneration(modelSpec: String, settings: ImageGenerationSettings) =
        ImageGenerationStore.set(context, modelSpec, settings)
}
