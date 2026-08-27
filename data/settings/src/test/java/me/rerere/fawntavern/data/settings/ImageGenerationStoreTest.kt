package me.rerere.fawntavern.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageGenerationStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("image_generation", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun legacySettingsDefaultToAutomaticQuality() {
        val legacy = JSONObject().put("provider::model", JSONObject()
            .put("count", 2)
            .put("aspectRatio", "1:1")
            .put("resolution", "2k"))
        context.getSharedPreferences("image_generation", Context.MODE_PRIVATE)
            .edit().putString("settings", legacy.toString()).commit()

        assertEquals("auto", ImageGenerationStore.get(context, "provider::model").quality)
    }

    @Test
    fun qualityIsRememberedPerModel() {
        ImageGenerationStore.set(
            context,
            "openai::gpt-image-2",
            ImageGenerationSettings(quality = "high"),
        )

        assertEquals("high", ImageGenerationStore.get(context, "openai::gpt-image-2").quality)
        assertEquals("auto", ImageGenerationStore.get(context, "openai::other").quality)
    }

    @Test
    fun unsupportedQualityFallsBackToAutomatic() {
        ImageGenerationStore.set(
            context,
            "openai::gpt-image-2",
            ImageGenerationSettings(quality = "ultra"),
        )

        assertEquals("auto", ImageGenerationStore.get(context, "openai::gpt-image-2").quality)
    }
}
