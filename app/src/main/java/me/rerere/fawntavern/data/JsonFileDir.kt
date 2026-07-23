package me.rerere.fawntavern.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * filesDir 子目录 JSON 文件仓库的共用文件操作，
 * 供 Character/Preset/WorldBook Repository 复用；调用方只保留领域逻辑。
 */
object JsonFileDir {

    fun dir(context: Context, dirName: String): File =
        File(context.filesDir, dirName).also { it.mkdirs() }

    fun file(context: Context, dirName: String, name: String): File =
        File(dir(context, dirName), "$name.json")

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
            if (newName.isBlank() || newName == oldName) return@withContext false
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
