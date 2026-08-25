package me.rerere.fawntavern.data.regex

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.JsonFileDir
import me.rerere.fawntavern.data.RESOURCE_ID_FIELD
import me.rerere.fawntavern.data.ensureResourceId
import me.rerere.fawntavern.data.newResourceId
import me.rerere.fawntavern.data.resourceId
import me.rerere.fawntavern.data.preset.PresetParser
import me.rerere.fawntavern.data.preset.RegexScript
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一组命名的正则脚本。[global] 为真时对所有聊天生效，否则只在被角色卡的
 * `enabled_regex_ids` 关联时生效。
 */
data class RegexSet(
    val name: String = "",
    val id: String = "",
    val global: Boolean = false,
    val scripts: List<RegexScript> = emptyList(),
)

/**
 * 正则集：`filesDir/regexsets/<名>.json`，角色卡按不可变资源 ID 关联。
 * 角色卡内嵌的 `extensions.regex_scripts` 与 `character_book` 同理降级为导入载荷。
 */
object RegexSetRepository {

    /** 角色卡抽内嵌正则、导出时回填都要拼这个目录，共享一份避免字符串各处硬编码 */
    const val SET_DIR = "regexsets"
    private val writeMutex = Mutex()

    fun setsDir(context: Context): File = JsonFileDir.dir(context, SET_DIR)

    suspend fun listNames(context: Context): List<String> = JsonFileDir.listNames(context, SET_DIR)

    suspend fun load(context: Context, name: String): RegexSet = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val file = JsonFileDir.file(context, SET_DIR, name)
            if (!file.exists()) throw IllegalStateException("正则集不存在: $name")
            val json = JSONObject(file.readText())
            if (json.resourceId().isBlank()) {
                json.ensureResourceId()
                JsonFileDir.atomicWriteText(file, json.toString(2))
            }
            parse(json, name)
        }
    }

    suspend fun loadById(context: Context, id: String): RegexSet =
        loadAll(context).firstOrNull { it.id == id }
            ?: throw IllegalStateException("正则不存在: $id")

    /** 全部正则集；单个文件坏掉只跳过它，不连累其余 */
    suspend fun loadAll(context: Context): List<RegexSet> = withContext(Dispatchers.IO) {
        listNames(context).mapNotNull { runCatching { load(context, it) }.getOrNull() }
    }

    /** 全局启用的集名（对所有聊天生效），生成前与卡关联的集合并去重 */
    suspend fun globalNames(context: Context): List<String> =
        loadAll(context).filter { it.global }.map { it.name }

    /** 保存正则集；原位 patch 已有文件，保留未知的顶层字段 */
    suspend fun save(context: Context, set: RegexSet) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val file = JsonFileDir.file(context, SET_DIR, set.name)
            val json = if (file.isFile) {
                runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
            } else {
                JSONObject()
            }
            JsonFileDir.atomicWriteText(file, fill(json, set).toString(2))
        }
    }

    /** 追加脚本，不重写已有数组项，保留其未建模字段。 */
    suspend fun appendScript(context: Context, name: String, script: RegexScript) =
        mutateScripts(context, name) { scripts ->
            scripts.put(PresetParser.serializeRegexScript(script))
        }

    /** 原位更新一条脚本，只覆盖已知字段，保留 SillyTavern 或插件附加的未知字段。 */
    suspend fun updateScript(context: Context, name: String, index: Int, script: RegexScript) =
        mutateScripts(context, name) { scripts ->
            require(index in 0 until scripts.length()) { "正则脚本不存在" }
            val target = scripts.optJSONObject(index)
                ?: JSONObject().also { scripts.put(index, it) }
            val serialized = PresetParser.serializeRegexScript(script)
            serialized.keys().forEach { key -> target.put(key, serialized.get(key)) }
        }

    /** 删除一条脚本，其余项保持原始 JSON。 */
    suspend fun deleteScript(context: Context, name: String, index: Int) =
        mutateScripts(context, name) { scripts ->
            require(index in 0 until scripts.length()) { "正则脚本不存在" }
            scripts.remove(index)
        }

    private suspend fun mutateScripts(
        context: Context,
        name: String,
        transform: (JSONArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val file = JsonFileDir.file(context, SET_DIR, name)
            require(file.isFile) { "正则集不存在: $name" }
            val json = JSONObject(file.readText())
            json.ensureResourceId()
            val scripts = json.optJSONArray("regex_scripts")
                ?: JSONArray().also { json.put("regex_scripts", it) }
            transform(scripts)
            JsonFileDir.atomicWriteText(file, json.toString(2))
        }
    }

    /** 新建空正则集；名称非法字符会被清洗、重名自动加序号 */
    suspend fun create(
        context: Context,
        requestedName: String,
        global: Boolean = false,
    ): RegexSet = withContext(Dispatchers.IO) {
        val displayName = requestedName.trim()
        require(displayName.isNotBlank()) { "正则集名称不能为空" }
        writeMutex.withLock {
            val name = JsonFileDir.uniqueName(context, SET_DIR, displayName, fallbackName())
            val set = RegexSet(id = newResourceId(), name = name, global = global)
            JsonFileDir.atomicWriteText(
                JsonFileDir.file(context, SET_DIR, name),
                fill(JSONObject(), set).toString(2),
            )
            set
        }
    }

    suspend fun delete(context: Context, name: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val file = JsonFileDir.file(context, SET_DIR, name)
            if (file.exists() && !file.delete()) {
                throw IllegalStateException("无法删除正则集: $name")
            }
        }
    }

    /** 重命名正则集，成功返回 true（目标名已存在或源不存在则失败） */
    suspend fun rename(context: Context, oldName: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                if (!JsonFileDir.rename(context, SET_DIR, oldName, newName)) return@withLock false
                val file = JsonFileDir.file(context, SET_DIR, newName)
                try {
                    val json = JSONObject(file.readText()).put("name", newName)
                    JsonFileDir.atomicWriteText(file, json.toString(2))
                    true
                } catch (error: Exception) {
                    JsonFileDir.rename(context, SET_DIR, newName, oldName)
                    throw error
                }
            }
        }

    suspend fun exportJsonBytes(context: Context, name: String): ByteArray = withContext(Dispatchers.IO) {
        load(context, name)
        JsonFileDir.file(context, SET_DIR, name).readBytes()
    }

    suspend fun ensureAllIds(context: Context) {
        listNames(context).forEach { load(context, it) }
    }

    suspend fun clear(context: Context) = JsonFileDir.clear(context, SET_DIR)

    private fun parse(json: JSONObject, name: String): RegexSet = RegexSet(
        id = json.resourceId(),
        name = name,
        global = json.optBoolean("global", false),
        scripts = parseScripts(json.optJSONArray("regex_scripts")),
    )

    private fun fill(json: JSONObject, set: RegexSet): JSONObject {
        val stableId = json.resourceId().ifBlank { set.id.ifBlank { newResourceId() } }
        return json
            .put(RESOURCE_ID_FIELD, stableId)
            .put("name", set.name)
            .put("global", set.global)
            .put("regex_scripts", JSONArray().apply {
                set.scripts.forEach { put(PresetParser.serializeRegexScript(it)) }
            })
    }

    /**
     * 导入正则集。文件既可能是本 App 导出的正则集（带 `regex_scripts` 数组），也可能是
     * SillyTavern 的单个正则脚本（顶层就是脚本对象）——后者包成只含一条的集。
     */
    suspend fun import(context: Context, uri: Uri): RegexSet = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            ?: throw IllegalStateException("无法读取文件")
        val json = JSONObject(text)
        val array = json.optJSONArray("regex_scripts")
        val scripts = if (array != null) {
            parseScripts(array)
        } else {
            listOf(withId(PresetParser.parseRegexScript(json)))
        }
        val requested = JsonFileDir.queryDisplayName(context, uri)
            ?.removeSuffix(".json")?.takeIf { it.isNotBlank() }
            ?: json.optString("name", "").takeIf { it.isNotBlank() }
            ?: scripts.firstOrNull()?.scriptName?.takeIf { it.isNotBlank() }
            ?: fallbackName()
        writeMutex.withLock {
            val name = JsonFileDir.uniqueName(context, SET_DIR, requested, fallbackName())
            val incomingId = json.resourceId()
            val set = RegexSet(
                id = incomingId.takeIf { value ->
                    value.isNotBlank() && value !in resourceIdsUnlocked(context)
                } ?: newResourceId(),
                name = name,
                global = json.optBoolean("global", false),
                scripts = scripts,
            )
            JsonFileDir.atomicWriteText(
                JsonFileDir.file(context, SET_DIR, name),
                fill(JSONObject(), set).toString(2),
            )
            set
        }
    }

    /**
     * 用一批脚本落一个新正则文件，返回实际文件名（重名自动加序号）。角色卡导入抽内嵌正则用；
     * 直接搬原始 JSONArray 而不过模型，未建模的脚本字段才不会丢。
     */
    suspend fun createFrom(
        context: Context,
        requestedName: String,
        scripts: JSONArray,
        global: Boolean = false,
    ): RegexSet = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val name = JsonFileDir.uniqueName(context, SET_DIR, requestedName, fallbackName())
            val set = RegexSet(id = newResourceId(), name = name, global = global)
            JsonFileDir.atomicWriteText(
                JsonFileDir.file(context, SET_DIR, name),
                JSONObject().put(RESOURCE_ID_FIELD, set.id).put("name", name).put("global", global)
                    .put("regex_scripts", scripts).toString(2),
            )
            set.copy(scripts = parseScripts(scripts))
        }
    }

    /** 原始脚本数组（不过模型），导出回填角色卡内嵌正则用，未建模的脚本字段才不会丢 */
    suspend fun rawScripts(context: Context, name: String): JSONArray? = withContext(Dispatchers.IO) {
        val file = JsonFileDir.file(context, SET_DIR, name)
        if (!file.isFile) return@withContext null
        runCatching { JSONObject(file.readText()).optJSONArray("regex_scripts") }.getOrNull()
    }

    private fun parseScripts(array: JSONArray?): List<RegexScript> = buildList {
        if (array == null) return@buildList
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { add(withId(PresetParser.parseRegexScript(it))) }
        }
    }

    /** 缺 id 的脚本补一个，UI 的编辑/删除按 id 定位 */
    private fun withId(script: RegexScript): RegexScript =
        if (script.id.isBlank()) script.copy(id = java.util.UUID.randomUUID().toString()) else script

    private fun resourceIdsUnlocked(context: Context): Set<String> = setsDir(context).listFiles()
        ?.asSequence()
        ?.filter { it.extension == "json" }
        ?.mapNotNull { runCatching { JSONObject(it.readText()).resourceId() }.getOrNull() }
        ?.filter(String::isNotBlank)
        ?.toSet()
        .orEmpty()

    private fun fallbackName() = "regexset_${System.currentTimeMillis()}"
}
