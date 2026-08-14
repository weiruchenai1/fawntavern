package me.rerere.fawntavern.data.preset

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

object PresetRepository {

    private const val PRESETS_DIR = "presets"
    private val writeMutex = Mutex()

    fun presetsDir(context: Context): File = JsonFileDir.dir(context, PRESETS_DIR)

    /** 列出所有已导入的预设名（文件名，不含 .json）。 */
    suspend fun listNames(context: Context): List<String> =
        JsonFileDir.listNames(context, PRESETS_DIR)

    suspend fun load(context: Context, name: String): StPreset = withContext(Dispatchers.IO) {
        val file = JsonFileDir.file(context, PRESETS_DIR, name)
        if (!file.exists()) throw IllegalStateException("预设文件不存在: $name")
        PresetParser.parse(JSONObject(file.readText()), name)
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
                JsonFileDir.atomicWriteText(JsonFileDir.file(context, PRESETS_DIR, it), text)
            }
        }
        PresetParser.parse(json, name)
    }

    /** 保存（覆盖）预设：全量回写采样参数、prompts 内容池与 prompt_order（保留其它字段）。 */
    suspend fun save(context: Context, preset: StPreset) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
        val file = JsonFileDir.file(context, PRESETS_DIR, preset.name)
        val root = if (file.exists()) JSONObject(file.readText()) else JSONObject()

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

        // 预设私有正则脚本：全量覆盖 regex_scripts（编辑器就地增删改，退出时随预设一起落盘）
        val regexArr = JSONArray()
        preset.regexScripts.forEach { regexArr.put(PresetParser.serializeRegexScript(it)) }
        root.put("regex_scripts", regexArr)

        JsonFileDir.atomicWriteText(file, root.toString(2))
        }
    }

    suspend fun delete(context: Context, name: String) =
        JsonFileDir.delete(context, PRESETS_DIR, name)

    /** 重命名预设，成功返回 true（目标名已存在或源不存在则失败） */
    suspend fun rename(context: Context, oldName: String, newName: String): Boolean =
        JsonFileDir.rename(context, PRESETS_DIR, oldName, newName)

    /** 清空所有预设 */
    suspend fun clear(context: Context) = JsonFileDir.clear(context, PRESETS_DIR)

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
}
