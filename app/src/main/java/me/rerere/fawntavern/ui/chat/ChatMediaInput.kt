package me.rerere.fawntavern.ui.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

private val IMAGE_FILE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif",
)

internal fun isImageAttachment(mimeType: String?, path: String?): Boolean {
    if (mimeType != null) return mimeType.startsWith("image/", ignoreCase = true)
    val extension = path?.substringAfterLast('/')?.substringAfterLast('.', missingDelimiterValue = "")
        ?: return false
    return extension.lowercase() in IMAGE_FILE_EXTENSIONS
}

internal class ChatMediaInput(
    private val context: Context,
) {
    fun isImage(uri: Uri): Boolean =
        isImageAttachment(context.contentResolver.getType(uri), uri.lastPathSegment)

    fun createCameraUri(now: Long = System.currentTimeMillis()): Uri {
        val file = File(cameraDirectory(), "photo_$now.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun discardCameraFile(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: return
        if (name.startsWith("photo_") && name.endsWith(".jpg")) {
            File(cameraDirectory(), name).delete()
        }
    }

    private fun cameraDirectory(): File = File(context.cacheDir, "photos").also { it.mkdirs() }
}
