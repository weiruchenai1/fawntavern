package me.rerere.fawntavern.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaInputTest {
    @Test
    fun mimeTypeTakesPriorityOverExtension() {
        assertTrue(isImageAttachment("image/png", "document.bin"))
        assertFalse(isImageAttachment("text/plain", "photo.jpg"))
    }

    @Test
    fun extensionIsUsedWhenProviderOmitsMimeType() {
        assertTrue(isImageAttachment(null, "folder/PHOTO.HEIC"))
        assertFalse(isImageAttachment(null, "folder/archive.zip"))
        assertFalse(isImageAttachment(null, null))
    }
}
