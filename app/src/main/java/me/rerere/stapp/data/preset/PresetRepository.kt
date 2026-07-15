package me.rerere.stapp.data.preset

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.stapp.data.JsonFileDir
import org.json.JSONObject
import java.io.File

object PresetRepository {

    private const val PRESETS_DIR = "presets"
    private const val REGEX_DIR = "regex"

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
        val name = JsonFileDir.queryDisplayName(context, uri)
            ?.removeSuffix(".json")?.takeIf { it.isNotBlank() }
            ?: "preset_${System.currentTimeMillis()}"
        JsonFileDir.file(context, PRESETS_DIR, name).writeText(text)
        PresetParser.parse(json, name)
    }

    /** 保存（覆盖）预设。 */
    suspend fun save(context: Context, preset: StPreset) = withContext(Dispatchers.IO) {
        // 重建原始 JSON，保留 prompts 结构（只更新顶层字段）
        val file = JsonFileDir.file(context, PRESETS_DIR, preset.name)
        val root = if (file.exists()) JSONObject(file.readText()) else JSONObject()

        root.put("chat_completion_source", preset.chatCompletionSource)
        root.put("openai_model", preset.openaiModel)
        root.put("claude_model", preset.claudeModel)
        root.put("google_model", preset.googleModel)
        root.put("custom_model", preset.customModel)
        root.put("openrouter_model", preset.openrouterModel)
        root.put("temperature", preset.temperature.toDouble())
        root.put("top_p", preset.topP.toDouble())
        root.put("frequency_penalty", preset.frequencyPenalty.toDouble())
        root.put("presence_penalty", preset.presencePenalty.toDouble())
        root.put("top_k", preset.topK)
        root.put("openai_max_context", preset.maxContext)
        root.put("openai_max_tokens", preset.maxTokens)
        root.put("stream_openai", preset.streamOpenai)

        file.writeText(root.toString(2))
    }

    suspend fun delete(context: Context, name: String) =
        JsonFileDir.delete(context, PRESETS_DIR, name)

    /** 重命名预设，成功返回 true（目标名已存在或源不存在则失败） */
    suspend fun rename(context: Context, oldName: String, newName: String): Boolean =
        JsonFileDir.rename(context, PRESETS_DIR, oldName, newName)

    /** 清空所有预设 */
    suspend fun clear(context: Context) = JsonFileDir.clear(context, PRESETS_DIR)

    suspend fun listRegexNames(context: Context): List<String> =
        JsonFileDir.listNames(context, REGEX_DIR)

    suspend fun loadRegex(context: Context, name: String): RegexScript = withContext(Dispatchers.IO) {
        val file = JsonFileDir.file(context, REGEX_DIR, name)
        val json = JSONObject(file.readText())
        PresetParser.parseRegexScript(json)
    }
}
