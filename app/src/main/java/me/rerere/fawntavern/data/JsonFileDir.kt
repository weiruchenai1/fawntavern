package me.rerere.fawntavern.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * filesDir 子目录 JSON 文件仓库的共用文件操作，
 * 供 Character/Preset/WorldBook Repository 复用；调用方只保留领域逻辑。
 */
object JsonFileDir {

    private val invalidNameChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    fun dir(context: Context, dirName: String): File {
        require(
            dirName.isNotBlank() && dirName != "." && dirName != ".." &&
                !invalidNameChars.containsMatchIn(dirName)
        ) { "Invalid JSON storage directory" }
        val root = context.filesDir.canonicalFile
        val result = File(root, dirName).canonicalFile
        require(result.parentFile == root) { "JSON storage directory escapes filesDir" }
        return result.also { it.mkdirs() }
    }

    fun file(context: Context, dirName: String, name: String): File {
        require(isValidName(name)) { "Invalid JSON file name" }
        val parent = dir(context, dirName).canonicalFile
        val target = File(parent, "$name.json").canonicalFile
        require(target.parentFile == parent) { "JSON file escapes its storage directory" }
        return target
    }

    fun isValidName(name: String): Boolean =
        name.isNotBlank() &&
            name == name.trim() &&
            name != "." && name != ".." &&
            !name.endsWith('.') &&
            !invalidNameChars.containsMatchIn(name)

    /** Turn an untrusted provider/model display name into one local path segment. */
    fun sanitizeName(name: String, fallback: String): String =
        name.replace(invalidNameChars, "_")
            .trim()
            .trim('.')
            .take(180)
            .trim()
            .trim('.')
            .ifBlank { fallback }

    /** Return a non-existing name so imports never overwrite data without confirmation. */
    fun uniqueName(context: Context, dirName: String, requestedName: String, fallback: String): String {
        val base = sanitizeName(requestedName, fallback)
        var candidate = base
        var suffix = 2
        while (file(context, dirName, candidate).exists()) {
            candidate = "$base ($suffix)"
            suffix++
        }
        return candidate
    }

    /** Write in the target directory, flush it, then replace the destination with one move. */
    fun atomicWriteText(target: File, content: String) {
        val parent = requireNotNull(target.parentFile).canonicalFile
        require(target.canonicalFile.parentFile == parent) { "JSON file escapes its storage directory" }
        parent.mkdirs()
        val temp = File.createTempFile(".${target.nameWithoutExtension}_", ".tmp", parent)
        try {
            FileOutputStream(temp).use { output ->
                OutputStreamWriter(output, StandardCharsets.UTF_8).buffered().use { writer ->
                    writer.write(content)
                    writer.flush()
                    output.fd.sync()
                }
            }
            try {
                Files.move(
                    temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    /** 列出目录下全部 JSON 文件名（不含扩展名），按字母序 */
    suspend fun listNames(context: Context, dirName: String): List<String> = withContext(Dispatchers.IO) {
        dir(context, dirName).listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sortedBy { it }
            ?: emptyList()
    }

    suspend fun delete(context: Context, dirName: String, name: String) = withContext(Dispatchers.IO) {
        file(context, dirName, name).delete()
        Unit
    }

    /** 重命名条目，成功返回 true（目标名已存在或源不存在则失败） */
    suspend fun rename(context: Context, dirName: String, oldName: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isValidName(oldName) || !isValidName(newName) || newName == oldName) return@withContext false
            val src = file(context, dirName, oldName)
            val dst = file(context, dirName, newName)
            if (!src.exists() || dst.exists()) return@withContext false
            src.renameTo(dst)
        }

    /** 清空目录下所有文件 */
    suspend fun clear(context: Context, dirName: String) = withContext(Dispatchers.IO) {
        dir(context, dirName).listFiles()?.forEach { it.delete() }
        Unit
    }

    /** 从 ContentResolver 查询 content URI 的真实文件名（含扩展名），查不到返回 null */
    fun queryDisplayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
}
