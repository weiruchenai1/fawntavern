package me.rerere.fawntavern.data.worldbook

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.JsonFileDir
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object WorldBookRepository {

    private const val WORLD_DIR = "worldbooks"
    private val writeMutex = Mutex()

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
        val fallback = "worldbook_${System.currentTimeMillis()}"
        val requestedName = JsonFileDir.queryDisplayName(context, uri)
            ?.removeSuffix(".json")?.takeIf { it.isNotBlank() }
            ?: json.optString("name", "").takeIf { it.isNotBlank() }
            ?: fallback
        val name = writeMutex.withLock {
            JsonFileDir.uniqueName(context, WORLD_DIR, requestedName, fallback).also {
                JsonFileDir.atomicWriteText(JsonFileDir.file(context, WORLD_DIR, it), text)
            }
        }
        WorldBookParser.parse(json, name)
    }

    /** 新建空世界书；名称非法字符会被清洗、重名自动加序号，返回落盘后的世界书 */
    suspend fun create(context: Context, requestedName: String): WorldBook = withContext(Dispatchers.IO) {
        val displayName = requestedName.trim()
        require(displayName.isNotBlank()) { "世界书名称不能为空" }
        writeMutex.withLock {
            val fallback = "worldbook_${System.currentTimeMillis()}"
            val name = JsonFileDir.uniqueName(context, WORLD_DIR, displayName, fallback)
            JsonFileDir.atomicWriteText(
                JsonFileDir.file(context, WORLD_DIR, name),
                JSONObject().put("entries", JSONObject()).toString(2),
            )
            WorldBook(name = name)
        }
    }

    suspend fun delete(context: Context, name: String) =
        JsonFileDir.delete(context, WORLD_DIR, name)

    /** 重命名世界书，成功返回 true（目标名已存在或源不存在则失败） */
    suspend fun rename(context: Context, oldName: String, newName: String): Boolean =
        JsonFileDir.rename(context, WORLD_DIR, oldName, newName)

    /** Export the original JSON bytes so unknown SillyTavern fields are preserved. */
    suspend fun exportJsonBytes(context: Context, name: String): ByteArray = withContext(Dispatchers.IO) {
        JsonFileDir.file(context, WORLD_DIR, name).readBytes()
    }

    /** 保存条目（就地 patch 原文件条目，只覆盖编辑过的键，保留 ST 私有/扩展字段）。 */
    suspend fun saveEntries(context: Context, name: String, entries: List<WorldBookEntry>) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
        val file = JsonFileDir.file(context, WORLD_DIR, name)
        require(file.isFile) { "世界书不存在: $name" }
        val json = JSONObject(file.readText())
        val entriesObj = json.optJSONObject("entries") ?: JSONObject()
        entries.forEach { entry ->
            val key = entry.id.toString()
            // 就地取原条目对象（可能以 id 为键，或与 uid 不一致）；缺失才新建，避免丢失原有私有字段
            val obj = entriesObj.optJSONObject(key)
                ?: entriesObj.keys().asSequence()
                    .mapNotNull { entriesObj.optJSONObject(it) }
                    .firstOrNull { it.optInt("uid", it.optInt("id", Int.MIN_VALUE)) == entry.id }
                ?: JSONObject().also {
                    it.put("uid", entry.id); it.put("id", entry.id)
                    entriesObj.put(key, it)
                }
            // 只覆盖模型承载的字段；未知字段（automationId/triggers/角色过滤/extensions…）原样保留
            obj.put("comment", entry.comment)
            obj.put("content", entry.content)
            obj.put("disable", !entry.enabled)
            obj.put("position", WorldBookPos.toStId(entry.position))
            obj.put("order", entry.insertionOrder)
            obj.put("key", JSONArray(entry.keys))
            obj.put("keysecondary", JSONArray(entry.keySecondary))
            obj.put("selectiveLogic", entry.selectiveLogic)
            obj.put("constant", entry.constant)
            obj.put("vectorized", entry.vectorized)
            obj.put("depth", entry.depth)
            obj.put("role", entry.role)
            obj.put("outletName", entry.outletName)
            obj.put("probability", entry.probability)
            // <100 需掷骰才生效：确保 ST 重新导入时不会因 useProbability=false 而忽略概率
            if (entry.probability < 100) obj.put("useProbability", true)
            entry.scanDepth?.let { obj.put("scanDepth", it) }
            entry.caseSensitive?.let { obj.put("caseSensitive", it) }
            entry.matchWholeWords?.let { obj.put("matchWholeWords", it) }
            // 递归 / 包含组 / 定时效果：过去仅在原文件已存在时保留，现随编辑一并落盘
            obj.put("excludeRecursion", entry.excludeRecursion)
            obj.put("preventRecursion", entry.preventRecursion)
            obj.put("delayUntilRecursion", entry.delayUntilRecursion)
            obj.put("group", entry.group)
            obj.put("groupOverride", entry.groupOverride)
            obj.put("groupWeight", entry.groupWeight)
            obj.put("useGroupScoring", entry.useGroupScoring)
            obj.put("sticky", entry.sticky)
            obj.put("cooldown", entry.cooldown)
            obj.put("delay", entry.delay)
        }
        // 删除：原文件里存在、但本次条目列表已不含的条目
        val keepIds = entries.mapTo(HashSet()) { it.id.toString() }
        entriesObj.keys().asSequence().toList().forEach { k ->
            val uid = entriesObj.optJSONObject(k)?.let { it.optInt("uid", it.optInt("id", Int.MIN_VALUE)).toString() }
            if (k !in keepIds && uid !in keepIds) entriesObj.remove(k)
        }
        json.put("entries", entriesObj)
        JsonFileDir.atomicWriteText(file, json.toString(2))
        }
    }

    /** 清空所有世界书 */
    suspend fun clear(context: Context) = JsonFileDir.clear(context, WORLD_DIR)
}
