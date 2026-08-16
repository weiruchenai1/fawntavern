package me.rerere.fawntavern.data.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.api.GeneratedImage

data class PersistedGeneratedImage(
    val path: String,
    val aspectRatio: String?,
)

/**
 * 附件落盘：发送时把 content URI 指向的内容拷入 filesDir/attachments/，
 * 返回 filesDir 相对路径存进 [ChatMessage] —— URI 的读权限是临时的，重启后失效，
 * 只有自有目录里的副本才能支撑历史消息展示与重答时的重复发送。
 */
object AttachmentStore {

    private const val DIR = "attachments"
    private const val MAX_DIM = 2048        // 图片最长边上限（重编码为 JPEG，控制 base64 体积）
    private const val JPEG_QUALITY = 85
    private const val MAX_FILE_BYTES = 20L * 1024 * 1024  // 非图片文件拷贝上限

    fun dir(context: Context): File = File(context.filesDir, DIR).also { it.mkdirs() }

    /** 发送前同步校验：非图片文件大小超过拷贝上限时拦截发送（返回 true），避免落盘失败后静默丢附件 */
    fun isTooLarge(context: Context, uri: Uri): Boolean {
        val size = querySize(context, uri) ?: return false  // 提供方不报大小 → 放行，由 persistFile 兜底
        return size > MAX_FILE_BYTES
    }

    private fun querySize(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
            } else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 图片：解码（API 28+ 走 ImageDecoder，自动处理 EXIF 旋转与降采样）后重编码为 JPEG。
     * 返回 filesDir 相对路径；解码失败返回 null。
     */
    suspend fun persistImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(context, uri) ?: return@withContext null
        val name = "img_${System.currentTimeMillis()}_${(0..999).random()}.jpg"
        val out = File(dir(context), name)
        try {
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            "$DIR/$name"
        } catch (_: Exception) {
            out.delete()
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** 将图片生成接口返回的内容保存为聊天图片附件。 */
    suspend fun persistGeneratedImage(context: Context, image: GeneratedImage): PersistedGeneratedImage? =
        withContext(Dispatchers.IO) {
            if (image.bytes.isEmpty()) return@withContext null
            val extension = when (image.mimeType.lowercase().substringBefore(';')) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "png"
            }
            val name = "generated_${System.currentTimeMillis()}_${(0..999).random()}.$extension"
            val out = File(dir(context), name)
            try {
                out.writeBytes(image.bytes)
                PersistedGeneratedImage(
                    path = "$DIR/$name",
                    aspectRatio = generatedImageAspectRatio(image.bytes),
                )
            } catch (_: Exception) {
                out.delete()
                null
            }
        }

    private fun generatedImageAspectRatio(bytes: ByteArray): String? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) return null
        val divisor = greatestCommonDivisor(width, height)
        return "${width / divisor}:${height / divisor}"
    }

    private fun greatestCommonDivisor(a: Int, b: Int): Int {
        var left = a
        var right = b
        while (right != 0) {
            val remainder = left % right
            left = right
            right = remainder
        }
        return left.coerceAtLeast(1)
    }

    /** 其它文件：原样拷贝，返回 (原始文件名, filesDir 相对路径)；过大或读取失败返回 null */
    suspend fun persistFile(context: Context, uri: Uri): MsgFile? = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(context, uri) ?: "file"
        val safeName = "file_${System.currentTimeMillis()}_${(0..999).random()}_" +
            displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").takeLast(60)
        val out = File(dir(context), safeName)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_FILE_BYTES) throw IllegalStateException("file too large")
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return@withContext null
            MsgFile(name = displayName, path = "$DIR/$safeName")
        } catch (_: Exception) {
            out.delete()
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    } catch (_: Exception) {
        uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE  // compress 需要软件位图
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
            // API 26/27：无 ImageDecoder，用 inSampleSize 降采样（不处理 EXIF 旋转）
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (_: Exception) {
        null
    }
}
