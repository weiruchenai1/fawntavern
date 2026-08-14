package me.rerere.fawntavern.ui.api

import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConfigControllerTest {
    @Test
    fun loadClearsInvalidSelectionAndConsumesRecoveryNotice() {
        val source = FakeApiConfigDataSource(
            config = ApiConfig(
                providers = listOf(ApiProvider(id = "provider", models = listOf(ModelInfo(id = "model")))),
                currentModel = "provider::missing",
            ),
            recovered = true,
        )

        val result = ApiConfigController(source).load()

        assertEquals("", result.config.currentModel)
        assertTrue(result.recovered)
        assertTrue(source.recoveryConsumed)
    }

    @Test
    fun savePersistsNormalizedConfig() {
        val source = FakeApiConfigDataSource(ApiConfig(), recovered = false)
        val controller = ApiConfigController(source)

        val saved = controller.save(ApiConfig(currentModel = "missing::model"))

        assertEquals("", saved.currentModel)
        assertEquals(saved, source.saved)
        assertFalse(source.recoveryConsumed)
    }

    private class FakeApiConfigDataSource(
        private val config: ApiConfig,
        private val recovered: Boolean,
    ) : ApiConfigDataSource {
        var saved: ApiConfig? = null
        var recoveryConsumed = false

        override fun load(): ApiConfig = config
        override fun save(config: ApiConfig) { saved = config }
        override fun consumeRecoveryNotice(): Boolean {
            recoveryConsumed = true
            return recovered
        }
    }
}
