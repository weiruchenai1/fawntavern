package me.rerere.fawntavern.ui.settings

import me.rerere.fawntavern.data.speech.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsConfigControllerTest {
    @Test
    fun loadFallsBackToFirstAvailableService() {
        val first = TTSProviderSetting.SystemTTS(id = "system")
        val second = TTSProviderSetting.OpenAI(id = "openai")
        val source = FakeTtsConfigDataSource(listOf(first, second), initialSelectedId = "missing")

        val state = TtsConfigController(source).load()

        assertEquals("system", state.selectedId)
        assertNull(source.savedSelectedId)
    }

    @Test
    fun removingSelectedServicePersistsFallbackAndProtectsLastService() {
        val first = TTSProviderSetting.SystemTTS(id = "system")
        val second = TTSProviderSetting.OpenAI(id = "openai")
        val source = FakeTtsConfigDataSource(listOf(first, second), initialSelectedId = "openai")
        val controller = TtsConfigController(source)

        val reduced = controller.remove(controller.load(), "openai")
        val unchanged = controller.remove(reduced, "system")

        assertEquals(listOf(first), unchanged.services)
        assertEquals("system", unchanged.selectedId)
        assertEquals("system", source.savedSelectedId)
    }

    private class FakeTtsConfigDataSource(
        private val initialServices: List<TTSProviderSetting>,
        private val initialSelectedId: String,
    ) : TtsConfigDataSource {
        var savedServices: List<TTSProviderSetting>? = null
        var savedSelectedId: String? = null

        override fun services(): List<TTSProviderSetting> = initialServices
        override fun saveServices(services: List<TTSProviderSetting>) { savedServices = services }
        override fun selectedId(): String = initialSelectedId
        override fun saveSelectedId(id: String) { savedSelectedId = id }
        override fun consumeRecoveryNotice(): Boolean = false
    }
}
