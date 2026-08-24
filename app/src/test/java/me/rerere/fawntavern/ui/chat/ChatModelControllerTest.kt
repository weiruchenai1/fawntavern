package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ModelInfo
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.api.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatModelControllerTest {
    @Test
    fun effectiveModelUsesCharacterThenDefaultThenApiFallback() {
        val source = FakeChatModelDataSource()
        val controller = ChatModelController(source)
        val config = ApiConfig(
            providers = listOf(
                provider("api", "model"),
                provider("default", "model"),
                provider("character", "model"),
            ),
            currentModel = "api::model",
        )

        assertEquals("api::model", controller.effectiveModelSpec("Character", config))
        source.defaultModel = "default::model"
        assertEquals("default::model", controller.effectiveModelSpec("Character", config))
        source.characterModels["Character"] = "character::model"
        assertEquals("character::model", controller.effectiveModelSpec("Character", config))
    }

    @Test
    fun selectPersistsAtCorrectScopeAndReturnsRememberedReasoning() {
        val source = FakeChatModelDataSource().apply {
            reasoningByModel["provider::model"] = ReasoningLevel.HIGH
        }
        val controller = ChatModelController(source)

        assertEquals(ReasoningLevel.HIGH, controller.select("Character", "provider::model"))
        assertEquals("provider::model", source.characterModels["Character"])
        controller.select(null, "global::model")
        assertEquals("global::model", source.defaultModel)
    }

    @Test
    fun resolveProviderRejectsDisabledOrMissingModels() {
        val source = FakeChatModelDataSource()
        val controller = ChatModelController(source)
        val disabled = ApiProvider(
            id = "provider",
            enabled = false,
            models = listOf(ModelInfo(id = "model")),
        )

        assertNull(controller.resolveProvider(null, ApiConfig(listOf(disabled), "provider::model")))

        val enabled = disabled.copy(enabled = true)
        assertEquals(enabled to "model", controller.resolveProvider(null, ApiConfig(listOf(enabled), "provider::model")))
        assertNull(controller.resolveProvider(null, ApiConfig(listOf(enabled), "provider::missing")))
    }

    private class FakeChatModelDataSource : ChatModelDataSource {
        val characterModels = mutableMapOf<String, String>()
        var defaultModel = ""
        val reasoningByModel = mutableMapOf<String, ReasoningLevel>()
        val imageGenerationByModel = mutableMapOf<String, ImageGenerationSettings>()

        override fun characterModel(characterName: String): String = characterModels[characterName].orEmpty()
        override fun saveCharacterModel(characterName: String, modelSpec: String) {
            characterModels[characterName] = modelSpec
        }
        override fun defaultChatModel(): String = defaultModel
        override fun saveDefaultChatModel(modelSpec: String) { defaultModel = modelSpec }
        override fun reasoning(modelSpec: String): ReasoningLevel =
            reasoningByModel[modelSpec] ?: ReasoningLevel.AUTO
        override fun saveReasoning(modelSpec: String, level: ReasoningLevel) {
            reasoningByModel[modelSpec] = level
        }
        override fun imageGeneration(modelSpec: String): ImageGenerationSettings =
            imageGenerationByModel[modelSpec] ?: ImageGenerationSettings()
        override fun saveImageGeneration(modelSpec: String, settings: ImageGenerationSettings) {
            imageGenerationByModel[modelSpec] = settings
        }
    }

    private fun provider(id: String, modelId: String) = ApiProvider(
        id = id,
        models = listOf(ModelInfo(id = modelId)),
    )
}
