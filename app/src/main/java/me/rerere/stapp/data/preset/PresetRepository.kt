package me.rerere.stapp.data.preset

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object PresetRepository {

    private const val PRESETS_DIR = "presets"
    private const val REGEX_DIR = "regex"

    fun presetsDir(context: Context): File =
        File(context.filesDir, PRESETS_DIR).also { it.mkdirs() }

    private fun regexDir(context: Context): File =
        File(context.filesDir, REGEX_DIR).also { it.mkdirs() }

    /** 列出所有已导入的预设名（文件名，不含 .json）。 */
    suspend fun listNames(context: Context): List<String> = withContext(Dispatchers.IO) {
        presetsDir(context).listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sortedBy { it }
            ?: emptyList()
    }

    suspend fun load(context: Context, name: String): StPreset = withContext(Dispatchers.IO) {
        val file = File(presetsDir(context), "$name.json")
        if (!file.exists()) throw IllegalStateException("预设文件不存在: $name")
        val json = JSONObject(file.readText())
        PresetParser.parse(json, name)
    }

    /** 从 content URI（文件选择器结果）导入预设。 */
    suspend fun import(context: Context, uri: Uri): StPreset = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            ?: throw IllegalStateException("无法读取文件")
        val json = JSONObject(text)
        // 从 ContentResolver 查询真实文件名
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
        val name = displayName
            ?.removeSuffix(".json")
            ?.takeIf { it.isNotBlank() }
            ?: "preset_${System.currentTimeMillis()}"
        val file = File(presetsDir(context), "$name.json")
        file.writeText(text)
        PresetParser.parse(json, name)
    }

    /** 保存（覆盖）预设。 */
    suspend fun save(context: Context, preset: StPreset) = withContext(Dispatchers.IO) {
        // 重建原始 JSON，保留 prompts 结构（只更新顶层字段）
        val file = File(presetsDir(context), "${preset.name}.json")
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

    suspend fun delete(context: Context, name: String) = withContext(Dispatchers.IO) {
        File(presetsDir(context), "$name.json").delete()
    }

    /** 重命名预设，成功返回 true（目标名已存在或源不存在则失败） */
    suspend fun rename(context: Context, oldName: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        if (newName.isBlank() || newName == oldName) return@withContext false
        val src = File(presetsDir(context), "$oldName.json")
        val dst = File(presetsDir(context), "$newName.json")
        if (!src.exists() || dst.exists()) return@withContext false
        src.renameTo(dst)
    }

    /** 清空所有预设 */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        presetsDir(context).listFiles()?.forEach { it.delete() }
        Unit
    }

    suspend fun listRegexNames(context: Context): List<String> = withContext(Dispatchers.IO) {
        regexDir(context).listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sortedBy { it }
            ?: emptyList()
    }

    suspend fun loadRegex(context: Context, name: String): RegexScript = withContext(Dispatchers.IO) {
        val file = File(regexDir(context), "$name.json")
        val json = JSONObject(file.readText())
        PresetParser.parseRegexScript(json)
    }
}
