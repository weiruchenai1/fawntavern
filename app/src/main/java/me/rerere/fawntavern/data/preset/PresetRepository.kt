package me.rerere.fawntavern.data.preset

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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PresetRepository {

    private const val PRESETS_DIR = "presets"
    private const val PREFS = "preset_repo"
    private const val KEY_DEFAULT_NAME = "default_preset_name"
    private val writeMutex = Mutex()

    fun presetsDir(context: Context): File = JsonFileDir.dir(context, PRESETS_DIR)

    /** 确保内置默认预设存在；文件名首次创建后固定，不随语言切换。 */
    suspend fun ensureDefaultPreset(context: Context, fallbackName: String): String = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val name = prefs.getString(KEY_DEFAULT_NAME, null)
                ?: fallbackName.also { value -> prefs.edit().putString(KEY_DEFAULT_NAME, value).apply() }
            val file = JsonFileDir.file(context, PRESETS_DIR, name)
            val root = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            if (!file.exists() || root.resourceId().isBlank()) {
                root.ensureResourceId()
                JsonFileDir.atomicWriteText(file, root.toString(2))
            }
            name
        }
    }

    fun defaultPresetName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEFAULT_NAME, null)

    suspend fun listNames(context: Context): List<String> {
        val names = JsonFileDir.listNames(context, PRESETS_DIR)
        val defaultName = defaultPresetName(context)
        return names.sortedBy { it != defaultName }
    }

    suspend fun load(context: Context, name: String): StPreset = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val file = JsonFileDir.file(context, PRESETS_DIR, name)
            if (!file.exists()) throw IllegalStateException("预设文件不存在: $name")
            val root = JSONObject(file.readText())
            if (root.resourceId().isBlank()) {
                root.ensureResourceId()
                JsonFileDir.atomicWriteText(file, root.toString(2))
            }
            PresetParser.parse(root, name)
        }
    }

    suspend fun loadById(context: Context, id: String): StPreset {
        listNames(context).forEach { name ->
            val preset = runCatching { load(context, name) }.getOrNull()
            if (preset?.id == id) return preset
        }
        throw IllegalStateException("预设不存在: $id")
    }

    /** 从 content URI（文件选择器结果）导入预设。 */
    suspend fun import(context: Context, uri: Uri): StPreset = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            ?: throw IllegalStateException("无法读取文件")
        val json = JSONObject(text)
        val fallback = "preset_${System.currentTimeMillis()}"
        val requestedName = JsonFileDir.queryDisplayName(context, uri)
            ?.removeSuffix(".json")?.takeIf { it.isNotBlank() }
            ?: fallback
        val name = writeMutex.withLock {
            JsonFileDir.uniqueName(context, PRESETS_DIR, requestedName, fallback).also {
                val incomingId = json.resourceId()
                val id = incomingId.takeIf { value ->
                    value.isNotBlank() && value !in resourceIdsUnlocked(context)
                } ?: newResourceId()
                json.put(RESOURCE_ID_FIELD, id)
                JsonFileDir.atomicWriteText(JsonFileDir.file(context, PRESETS_DIR, it), json.toString(2))
            }
        }
        PresetParser.parse(json, name)
    }

    suspend fun create(context: Context, requestedName: String): StPreset = withContext(Dispatchers.IO) {
        val displayName = requestedName.trim()
        require(displayName.isNotBlank()) { "Preset name cannot be empty" }
        val fallback = "preset_${System.currentTimeMillis()}"
        val (name, root) = writeMutex.withLock {
            val name = JsonFileDir.uniqueName(context, PRESETS_DIR, displayName, fallback)
            val root = JSONObject().put(RESOURCE_ID_FIELD, newResourceId())
            JsonFileDir.atomicWriteText(
                JsonFileDir.file(context, PRESETS_DIR, name),
                root.toString(2),
            )
            name to root
        }
        PresetParser.parse(root, name)
    }

    /** 保存（覆盖）预设：全量回写采样参数、prompts 内容池与 prompt_order（保留其它字段）。 */
    suspend fun save(context: Context, preset: StPreset) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
        val file = JsonFileDir.file(context, PRESETS_DIR, preset.name)
        val root = if (file.exists()) JSONObject(file.readText()) else JSONObject()
        val stableId = root.resourceId().ifBlank { preset.id.ifBlank { newResourceId() } }
        root.put(RESOURCE_ID_FIELD, stableId)

        // 采样 / 上下文参数
        root.put("chat_completion_source", preset.chatCompletionSource)
        root.put("openai_model", preset.openaiModel)
        root.put("claude_model", preset.claudeModel)
        root.put("google_model", preset.googleModel)
        root.put("custom_model", preset.customModel)
        root.put("openrouter_model", preset.openrouterModel)
        root.put("temperature", preset.temperature.toDouble())
        root.put("top_p", preset.topP.toDouble())
        root.put("top_k", preset.topK)
        root.put("top_a", preset.topA.toDouble())
        root.put("min_p", preset.minP.toDouble())
        root.put("frequency_penalty", preset.frequencyPenalty.toDouble())
        root.put("presence_penalty", preset.presencePenalty.toDouble())
        root.put("repetition_penalty", preset.repetitionPenalty.toDouble())
        root.put("seed", preset.seed)
        root.put("openai_max_context", preset.maxContext)
        root.put("openai_max_tokens", preset.maxTokens)
        root.put("stream_openai", preset.streamOpenai)

        // prompts[] 内容池：就地 patch 已有对象（保留 injection_order/injection_trigger 等未知字段），新增追加、删除剔除
        val existingById = HashMap<String, JSONObject>()
        root.optJSONArray("prompts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                existingById[o.optString("identifier")] = o
            }
        }
        val promptsArr = JSONArray()
        preset.prompts.forEach { p ->
            val o = existingById[p.identifier] ?: JSONObject().put("identifier", p.identifier)
            o.put("name", p.name)
            o.put("role", p.role)
            o.put("content", p.content)
            o.put("system_prompt", p.systemPrompt)
            o.put("marker", p.marker)
            o.put("enabled", p.enabled)
            o.put("injection_position", p.injectionPosition)
            o.put("injection_depth", p.injectionDepth)
            o.put("forbid_overrides", p.forbidOverrides)
            promptsArr.put(o)
        }
        root.put("prompts", promptsArr)

        // prompt_order：主排序组（100001 优先，否则首组）按编辑后的 prompts 顺序 + enabled 全量重建
        // （承载拖拽排序与开关）；其它角色分组原样保留，仅剔除已删除、追加新增，不受拖拽影响。
        val orderedIds = preset.prompts.map { it.identifier }
        val idSet = orderedIds.toSet()
        val enabledById = preset.prompts.associate { it.identifier to it.enabled }
        fun mainOrderJson(): JSONArray {
            val arr = JSONArray()
            orderedIds.forEach { id ->
                arr.put(JSONObject().put("identifier", id).put("enabled", enabledById[id] ?: true))
            }
            return arr
        }
        val orderArr = JSONArray()
        val existing = root.optJSONArray("prompt_order")
        if (existing != null && existing.length() > 0) {
            // 定位主组下标（characterId==100001 优先，否则第 0 组，与 PresetParser/PromptBuilder 一致）
            var mainIdx = 0
            for (i in 0 until existing.length()) {
                if (existing.optJSONObject(i)?.optInt("character_id", 100001) == 100001) { mainIdx = i; break }
            }
            for (i in 0 until existing.length()) {
                val grp = existing.optJSONObject(i) ?: continue
                val cid = grp.optInt("character_id", 100001)
                val newOrder = if (i == mainIdx) {
                    mainOrderJson()
                } else {
                    // 其它组：保留既有顺序与 enabled，仅剔除已删/重复，追加新增（默认启用）
                    val o2 = JSONArray()
                    val seen = mutableSetOf<String>()
                    grp.optJSONArray("order")?.let { o ->
                        for (j in 0 until o.length()) {
                            val t = o.optJSONObject(j) ?: continue
                            val id = t.optString("identifier")
                            if (id.isBlank() || id !in idSet || id in seen) continue
                            seen.add(id)
                            o2.put(JSONObject().put("identifier", id).put("enabled", t.optBoolean("enabled", true)))
                        }
                    }
                    orderedIds.filter { it !in seen }.forEach { id ->
                        o2.put(JSONObject().put("identifier", id).put("enabled", enabledById[id] ?: true))
                    }
                    o2
                }
                orderArr.put(JSONObject().put("character_id", cid).put("order", newOrder))
            }
        } else {
            orderArr.put(JSONObject().put("character_id", 100001).put("order", mainOrderJson()))
        }
        root.put("prompt_order", orderArr)

        // 预设私有正则遵循 SillyTavern 的 extensions.regex_scripts 结构。
        // 原位修改 extensions 对象，确保其他扩展拥有的数据在保存时不丢失。
        val regexArr = JSONArray()
        preset.regexScripts.forEach { regexArr.put(PresetParser.serializeRegexScript(it)) }
        val extensions = root.optJSONObject("extensions") ?: JSONObject()
        extensions.put("regex_scripts", regexArr)
        root.put("extensions", extensions)
        root.remove("regex_scripts")

        JsonFileDir.atomicWriteText(file, root.toString(2))
        }
    }

    suspend fun delete(context: Context, name: String) {
        if (name != defaultPresetName(context)) JsonFileDir.delete(context, PRESETS_DIR, name)
    }

    /** Export the original preset JSON so unknown SillyTavern fields are preserved. */
    suspend fun exportJsonBytes(context: Context, name: String): ByteArray = withContext(Dispatchers.IO) {
        load(context, name)
        JsonFileDir.file(context, PRESETS_DIR, name).readBytes()
    }

    suspend fun ensureAllIds(context: Context) {
        listNames(context).forEach { load(context, it) }
    }

    /** 重命名预设，成功返回 true（目标名已存在或源不存在则失败） */
    suspend fun rename(context: Context, oldName: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val wasDefault = oldName == defaultPresetName(context)
                if (!JsonFileDir.rename(context, PRESETS_DIR, oldName, newName)) {
                    return@withLock false
                }
                try {
                    if (wasDefault) {
                        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(KEY_DEFAULT_NAME, newName).commit()
                        if (!saved) throw IllegalStateException("Unable to save the default preset name")
                    }
                    true
                } catch (_: Exception) {
                    runCatching { JsonFileDir.rename(context, PRESETS_DIR, newName, oldName) }
                    false
                }
            }
        }

    /** 清空用户预设，保留内置默认预设。 */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val keep = defaultPresetName(context)
            presetsDir(context).listFiles()?.forEach { file ->
                if (file.nameWithoutExtension != keep) file.delete()
            }
        }
        Unit
    }

    /**
     * 从 content URI 读取并解析一个独立的 ST 正则脚本文件，返回内存对象（不落盘）。
     * 调用方负责把它加入某个预设的 regexScripts 后随预设一起保存——正则按预设隔离，
     * 不再有全局 regex/ 目录。id 为空时补一个 UUID，供列表/编辑做稳定标识。
     */
    suspend fun parseRegexUri(context: Context, uri: Uri): RegexScript = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            ?: throw IllegalStateException("无法读取文件")
        val parsed = PresetParser.parseRegexScript(JSONObject(text))
        if (parsed.id.isBlank()) parsed.copy(id = java.util.UUID.randomUUID().toString()) else parsed
    }

    private fun resourceIdsUnlocked(context: Context): Set<String> = presetsDir(context).listFiles()
        ?.asSequence()
        ?.filter { it.extension == "json" }
        ?.mapNotNull { runCatching { JSONObject(it.readText()).resourceId() }.getOrNull() }
        ?.filter(String::isNotBlank)
        ?.toSet()
        .orEmpty()
}
