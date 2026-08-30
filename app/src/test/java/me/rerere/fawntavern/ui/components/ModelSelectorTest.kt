package me.rerere.fawntavern.ui.components

import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ModelInfo
import me.rerere.fawntavern.data.api.ModelType
import me.rerere.fawntavern.ui.api.replaceVisibleProviderOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelSelectorTest {
    @Test
    fun selectedModelPositionIncludesProviderHeadersAndEmptyGroups() {
        val providers = listOf(
            ApiProvider(id = "empty"),
            ApiProvider(
                id = "provider",
                models = listOf(ModelInfo(id = "first"), ModelInfo(id = "selected")),
            ),
        )

        assertEquals(4, selectedModelListPosition(providers, "provider::selected"))
    }

    @Test
    fun missingSelectionHasNoTargetPosition() {
        val providers = listOf(
            ApiProvider(id = "provider", models = listOf(ModelInfo(id = "model"))),
        )

        assertNull(selectedModelListPosition(providers, "provider::missing"))
    }

    @Test
    fun categoryReorderPreservesHiddenProviderPositions() {
        val chat = ApiProvider(id = "chat")
        val hidden = ApiProvider(id = "hidden")
        val image = ApiProvider(id = "image")

        val reordered = replaceVisibleProviderOrder(
            all = listOf(chat, hidden, image),
            reordered = listOf(image, chat),
        )

        assertEquals(listOf("image", "hidden", "chat"), reordered.map { it.id })
    }

    @Test
    fun selectorOpensOnTheCurrentModelsCategory() {
        val providers = listOf(
            ApiProvider(
                id = "provider",
                models = listOf(
                    ModelInfo(id = "chat", type = ModelType.CHAT),
                    ModelInfo(id = "image", type = ModelType.IMAGE),
                ),
            ),
        )

        assertEquals(ModelType.IMAGE, modelTypeForSelection(providers, "provider::image"))
        assertEquals(ModelType.CHAT, modelTypeForSelection(providers, "provider::missing"))
    }

    @Test
    fun selectorCategoryKeepsOnlyProvidersWithMatchingModels() {
        val providers = listOf(
            ApiProvider(id = "chat", models = listOf(ModelInfo(id = "text"))),
            ApiProvider(
                id = "mixed",
                models = listOf(
                    ModelInfo(id = "text"),
                    ModelInfo(id = "image", type = ModelType.IMAGE),
                ),
            ),
        )

        val imageProviders = providersForModelType(providers, ModelType.IMAGE)

        assertEquals(listOf("mixed"), imageProviders.map { it.id })
        assertEquals(listOf("image"), imageProviders.single().models.map { it.id })
    }
}
