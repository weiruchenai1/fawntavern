package me.rerere.fawntavern.ui.settings

import me.rerere.fawntavern.data.api.ApiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultModelControllerTest {
    @Test
    fun updatesAndResetsEachRoleIndependently() {
        val source = FakeDefaultModelDataSource()
        val controller = DefaultModelController(source)
        val initial = controller.load()

        val modeled = controller.setModel(initial, DefaultModelRole.TITLE, "provider::model")
        val prompted = controller.setPrompt(modeled, DefaultModelRole.TITLE, "prompt")
        val withTranslation = controller.setModel(
            prompted,
            DefaultModelRole.TRANSLATION,
            "translation-provider::translation-model",
        )

        assertEquals("provider::model", withTranslation.entry(DefaultModelRole.TITLE).model)
        assertEquals("prompt", withTranslation.entry(DefaultModelRole.TITLE).prompt)
        assertEquals(
            "translation-provider::translation-model",
            withTranslation.entry(DefaultModelRole.TRANSLATION).model,
        )

        val reset = controller.reset(withTranslation, DefaultModelRole.TITLE)

        assertEquals(DefaultModelEntry(), reset.entry(DefaultModelRole.TITLE))
        assertEquals(DefaultModelEntry(), reset.entry(DefaultModelRole.CHAT))
        assertEquals(
            "translation-provider::translation-model",
            reset.entry(DefaultModelRole.TRANSLATION).model,
        )
        assertEquals(DefaultModelRole.TITLE, source.resetRole)
        assertTrue(controller.defaultPrompt(DefaultModelRole.TITLE).isNotBlank())
        assertTrue(controller.defaultPrompt(DefaultModelRole.TRANSLATION).contains("{language}"))
    }

    private class FakeDefaultModelDataSource : DefaultModelDataSource {
        val entries = DefaultModelRole.entries.associateWith { DefaultModelEntry() }.toMutableMap()
        var resetRole: DefaultModelRole? = null

        override fun apiConfig(): ApiConfig = ApiConfig()
        override fun entry(role: DefaultModelRole): DefaultModelEntry = entries.getValue(role)
        override fun setModel(role: DefaultModelRole, model: String) {
            entries[role] = entries.getValue(role).copy(model = model)
        }
        override fun setPrompt(role: DefaultModelRole, prompt: String) {
            entries[role] = entries.getValue(role).copy(prompt = prompt)
        }
        override fun reset(role: DefaultModelRole) {
            resetRole = role
            entries[role] = DefaultModelEntry()
        }
    }
}
