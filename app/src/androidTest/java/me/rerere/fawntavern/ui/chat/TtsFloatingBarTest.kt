package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.settings.ThemeMode
import me.rerere.fawntavern.data.speech.TtsUiState
import me.rerere.fawntavern.ui.theme.FawnTavernTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsFloatingBarTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun switchingThemeUpdatesFloatingBarSurface() {
        val theme = mutableStateOf(ThemeMode.LIGHT)
        compose.setContent {
            FawnTavernTheme(themeMode = theme.value) {
                TestBar()
            }
        }

        compose.onNodeWithTag(TtsFloatingBarTag)
            .captureToImage()
            .assertHasLightSurface()

        compose.runOnUiThread { theme.value = ThemeMode.DARK }
        compose.waitForIdle()

        compose.onNodeWithTag(TtsFloatingBarTag)
            .captureToImage()
            .assertHasDarkSurface()
    }

    @Test
    fun playPauseButtonUsesCurrentPlaybackState() {
        var pauses = 0
        var resumes = 0
        val state = mutableStateOf(TtsUiState(speaking = true))
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        compose.setContent {
            FawnTavernTheme(themeMode = ThemeMode.LIGHT) {
                TestBar(
                    state = state.value,
                    onPause = { pauses++ },
                    onResume = { resumes++ },
                )
            }
        }

        compose.onNodeWithContentDescription(resources.getString(R.string.tts_pause)).performClick()
        assertEquals(1, pauses)
        assertEquals(0, resumes)

        compose.runOnUiThread { state.value = state.value.copy(paused = true) }
        compose.onNodeWithContentDescription(resources.getString(R.string.tts_resume)).performClick()
        assertEquals(1, pauses)
        assertEquals(1, resumes)
    }

    @androidx.compose.runtime.Composable
    private fun TestBar(
        state: TtsUiState = TtsUiState(speaking = true),
        onPause: () -> Unit = {},
        onResume: () -> Unit = {},
    ) {
        TtsFloatingBar(
            state = state,
            onPause = onPause,
            onResume = onResume,
            onStop = {},
            onFastForward = {},
            onCycleSpeed = {},
        )
    }

    private fun ImageBitmap.assertHasLightSurface() {
        assertTrue("Expected a light floating-bar surface", countPixels { r, g, b ->
            r >= 230 && g >= 230 && b >= 230
        } >= width)
    }

    private fun ImageBitmap.assertHasDarkSurface() {
        assertTrue("Expected a dark floating-bar surface", countPixels { r, g, b ->
            r <= 64 && g <= 64 && b <= 64
        } >= width)
    }

    private fun ImageBitmap.countPixels(predicate: (Int, Int, Int) -> Boolean): Int {
        val pixels = toPixelMap()
        var matches = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = pixels[x, y].toArgb()
                if (predicate(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)) matches++
            }
        }
        return matches
    }
}
