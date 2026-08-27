package me.rerere.fawntavern.ui.chat

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatInputStateHolderTest {
    @Test
    fun restoreDraftPrependsSentTextAndDeduplicatesAttachments() {
        val existing = Attachment(Uri.parse("content://existing"), isImage = false)
        val restored = Attachment(Uri.parse("content://restored"), isImage = true)
        val holder = ChatInputStateHolder().apply {
            text = "new draft"
            addAttachments(listOf(existing))
        }

        holder.restoreDraft("sent draft", listOf(existing, restored))

        assertEquals("sent draft\nnew draft", holder.text)
        assertEquals(listOf(restored, existing), holder.attachments)
    }

    @Test
    fun finishingEditClearsEditMarkerAndText() {
        val holder = ChatInputStateHolder()
        holder.beginEditing(10L, "message")

        holder.finishEditing()

        assertEquals(null, holder.editingTimestamp)
        assertTrue(holder.text.isEmpty())
    }
}
