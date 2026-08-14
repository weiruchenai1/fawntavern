package me.rerere.fawntavern.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatUserProfileControllerTest {
    @Test
    fun saveNormalizesBlankNamesBeforeWriting() {
        val source = FakeProfileDataSource()
        val controller = ChatUserProfileController(source)

        controller.save("   ", "A profile")

        assertEquals("user", source.savedName)
        assertEquals("A profile", source.savedDescription)
    }

    private class FakeProfileDataSource : ChatUserProfileDataSource {
        var savedName: String? = null
        var savedDescription: String? = null

        override fun loadName(): String = "user"

        override fun load() = ChatUserProfile("user", "", 0, null)

        override fun save(name: String, description: String) {
            savedName = name
            savedDescription = description
        }

        override suspend fun saveAvatar(uri: Uri): Bitmap? = null

        override suspend fun deleteAvatar() = Unit
    }
}
