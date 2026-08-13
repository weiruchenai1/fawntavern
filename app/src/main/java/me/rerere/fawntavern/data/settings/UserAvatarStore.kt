package me.rerere.fawntavern.data.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 用户头像的受限尺寸存储，避免直接把外部文件完整读入内存。 */
object UserAvatarStore {
    private const val MAX_DIM = 1024
    private const val JPEG_QUALITY = 90

    suspend fun save(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(context, uri) ?: return@withContext null
        val dir = File(context.filesDir, "avatars").also { it.mkdirs() }
        val target = File(dir, "user_avatar.jpg")
        val previous = UserProfileStore.getAvatarPath(context)?.let(::File)
        val temp = File.createTempFile("avatar_", ".tmp", dir)
        try {
            temp.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
            }
            try {
                Files.move(
                    temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            UserProfileStore.setAvatarPath(context, target.absolutePath)
            if (previous != null && previous.absolutePath != target.absolutePath) previous.delete()
            BitmapFactory.decodeFile(target.absolutePath)
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
            bitmap.recycle()
        }
    }

    fun load(context: Context, targetPx: Int = 256): Bitmap? {
        val path = UserProfileStore.getAvatarPath(context) ?: return null
        val file = File(path).takeIf { it.isFile } ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        }
    }

    fun delete(context: Context) {
        UserProfileStore.getAvatarPath(context)?.let(::File)?.delete()
        UserProfileStore.setAvatarPath(context, null)
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maxSide = maxOf(info.size.width, info.size.height)
                if (maxSide > MAX_DIM) {
                    val scale = MAX_DIM.toFloat() / maxSide
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIM) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (_: Exception) {
        null
    }
}
