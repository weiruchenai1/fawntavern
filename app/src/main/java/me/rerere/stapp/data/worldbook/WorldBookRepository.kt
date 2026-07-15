package me.rerere.stapp.data.worldbook

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.stapp.data.JsonFileDir
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object WorldBookRepository {

    private const val WORLD_DIR = "worldbooks"

    fun worldDir(context: Context): File = JsonFileDir.dir(context, WORLD_DIR)

    suspend fun listNames(context: Context): List<String> =
        JsonFileDir.listNames(context, WORLD_DIR)

    suspend fun load(context: Context, name: String): WorldBook = withContext(Dispatchers.IO) {
        val file = JsonFileDir.file(context, WORLD_DIR, name)
        if (!file.exists()) throw IllegalStateException("世界书不存在: $name")
        WorldBookParser.parse(JSONObject(file.readText()), name)
    }

    suspend fun import(context: Context, uri: Uri): WorldBook = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            ?: throw IllegalStateException("无法读取文件")
        val json = JSONObject(text)
        val name = JsonFileDir.queryDisplayName(context, uri)
            ?.removeSuffix(".json")?.takeIf { it.isNotBlank() }
            ?: json.optString("name", "").takeIf { it.isNotBlank() }
            ?: "worldbook_${System.currentTimeMillis()}"
        JsonFileDir.file(context, WORLD_DIR, name).writeText(text)
        WorldBookParser.parse(json, name)
    }

    suspend fun delete(context: Context, name: String) =
        JsonFileDir.delete(context, WORLD_DIR, name)

    /** 重命名世界书，成功返回 true（目标名已存在或源不存在则失败） */
    suspend fun rename(context: Context, oldName: String, newName: String): Boolean =
        JsonFileDir.rename(context, WORLD_DIR, oldName, newName)

    /** 保存条目（回写原文件的 entries，保留其它字段）。字段名按 ST 世界书文件格式写，保证导出后 ST 还能导入 */
    suspend fun saveEntries(context: Context, name: String, entries: List<WorldBookEntry>) = withContext(Dispatchers.IO) {
        val file = JsonFileDir.file(context, WORLD_DIR, name)
        if (!file.exists()) return@withContext
        val json = JSONObject(file.readText())
        val entriesObj = json.optJSONObject("entries") ?: JSONObject()
        entries.forEach { entry ->
            entriesObj.put(entry.id.toString(), JSONObject().apply {
                put("uid", entry.id)
                put("id", entry.id)
                put("comment", entry.comment)
                put("content", entry.content)
                put("disable", !entry.enabled)
                put("position", when (entry.position) {
                    "before_char" -> 0
                    "at_depth" -> 4
                    else -> 1
                })
                put("order", entry.insertionOrder)
                put("key", JSONArray(entry.keys))
                put("keysecondary", JSONArray(entry.keySecondary))
                put("selectiveLogic", entry.selectiveLogic)
                put("constant", entry.constant)
                put("depth", entry.depth)
                put("probability", entry.probability)
                put("useProbability", true)
                entry.scanDepth?.let { put("scanDepth", it) }
                entry.caseSensitive?.let { put("caseSensitive", it) }
                entry.matchWholeWords?.let { put("matchWholeWords", it) }
            })
        }
        json.put("entries", entriesObj)
        file.writeText(json.toString(2))
    }

    /** 清空所有世界书 */
    suspend fun clear(context: Context) = JsonFileDir.clear(context, WORLD_DIR)
}
