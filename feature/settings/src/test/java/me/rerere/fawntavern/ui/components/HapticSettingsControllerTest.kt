package me.rerere.fawntavern.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticSettingsControllerTest {
    @Test
    fun readsLatestValueForEveryFeedbackRequest() {
        var enabled = true
        val controller = HapticSettingsController { enabled }

        assertTrue(controller.longPressEnabled())
        enabled = false
        assertFalse(controller.longPressEnabled())
    }
}
