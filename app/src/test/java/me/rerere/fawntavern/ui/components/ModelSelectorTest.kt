package me.rerere.fawntavern.ui.components

import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ModelInfo
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
}
