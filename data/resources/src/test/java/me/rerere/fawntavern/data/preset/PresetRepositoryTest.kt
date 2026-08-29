package me.rerere.fawntavern.data.preset

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PresetRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PresetRepository.presetsDir(context).deleteRecursively()
    }

    @Test
    fun saveMigratesLegacyRegexAndPreservesOtherExtensions() = runBlocking {
        val file = File(PresetRepository.presetsDir(context), "Compatible.json")
        file.writeText(
            """
            {
              "extensions": {"third_party": {"enabled": true}},
              "regex_scripts": [{"id": "legacy"}]
            }
            """.trimIndent()
        )
        val preset = PresetRepository.load(context, "Compatible")

        PresetRepository.save(context, preset)

        val saved = JSONObject(file.readText())
        assertFalse(saved.has("regex_scripts"))
        assertEquals(
            "legacy",
            saved.getJSONObject("extensions").getJSONArray("regex_scripts")
                .getJSONObject(0).getString("id")
        )
        assertEquals(
            true,
            saved.getJSONObject("extensions").getJSONObject("third_party").getBoolean("enabled")
        )
    }

    @Test
    fun createAssignsUniqueIdsAndRenameKeepsIdentity() = runBlocking {
        val first = PresetRepository.create(context, "Preset")
        val second = PresetRepository.create(context, "Preset")

        assertTrue(first.id.isNotBlank())
        assertNotEquals(first.id, second.id)
        assertTrue(PresetRepository.rename(context, first.name, "Renamed"))
        assertEquals(first.id, PresetRepository.load(context, "Renamed").id)
    }

    @Test
    fun savePersistsGenerationParameterSwitches() = runBlocking {
        val preset = PresetRepository.create(context, "Sampling").copy(
            sendTemperature = false,
            sendTopP = false,
            sendTopK = false,
            sendFrequencyPenalty = false,
            sendPresencePenalty = false,
            sendSeed = false,
            sendMaxTokens = false,
        )

        PresetRepository.save(context, preset)

        val saved = PresetRepository.load(context, preset.name)
        assertFalse(
            saved.sendTemperature || saved.sendTopP || saved.sendTopK ||
                saved.sendFrequencyPenalty || saved.sendPresencePenalty || saved.sendSeed ||
                saved.sendMaxTokens
        )
    }
}
