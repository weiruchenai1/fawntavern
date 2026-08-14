package me.rerere.fawntavern.ui.chat

import android.net.Uri
import kotlinx.coroutines.runBlocking
import me.rerere.fawntavern.data.chat.MsgFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatAttachmentCoordinatorTest {
    @Test
    fun persistsImagesAndFilesInInputOrder() = runBlocking {
        val source = FakeAttachmentDataSource()
        val coordinator = ChatAttachmentCoordinator(source)
        val attachments = listOf(
            Attachment(Uri.parse("content://image"), isImage = true),
            Attachment(Uri.parse("content://file"), isImage = false),
        )

        val result = coordinator.persist(attachments)

        assertEquals(listOf("attachments/image.jpg"), result?.images)
        assertEquals(listOf(MsgFile("file.txt", "attachments/file.txt")), result?.files)
        assertEquals(emptyList<Uri>(), source.cleaned)
    }

    @Test
    fun failedPersistenceCleansPartialFilesAndReturnsNull() = runBlocking {
        val source = FakeAttachmentDataSource(failAt = Uri.parse("content://file"))
        val coordinator = ChatAttachmentCoordinator(source)
        val attachments = listOf(
            Attachment(Uri.parse("content://image"), isImage = true),
            Attachment(Uri.parse("content://file"), isImage = false),
        )

        assertNull(coordinator.persist(attachments))
        assertTrue(source.cleaned.isEmpty())
        assertEquals(1, source.collectCalls)
    }

    @Test
    fun oversizedCheckIgnoresImages() {
        val source = FakeAttachmentDataSource(oversized = setOf(Uri.parse("content://large")))
        val coordinator = ChatAttachmentCoordinator(source)

        assertTrue(coordinator.hasOversizedFile(listOf(Attachment(Uri.parse("content://large"), false))))
        assertEquals(false, coordinator.hasOversizedFile(listOf(Attachment(Uri.parse("content://large"), true))))
    }

    private class FakeAttachmentDataSource(
        private val failAt: Uri? = null,
        private val oversized: Set<Uri> = emptySet(),
    ) : ChatAttachmentDataSource {
        val cleaned = mutableListOf<Uri>()
        var collectCalls = 0

        override fun isTooLarge(uri: Uri): Boolean = uri in oversized
        override suspend fun persistImage(uri: Uri): String? =
            if (uri == failAt) null else "attachments/image.jpg"
        override suspend fun persistFile(uri: Uri): MsgFile? =
            if (uri == failAt) null else MsgFile("file.txt", "attachments/file.txt")
        override suspend fun collectUnused() {
            collectCalls++
        }
    }
}
