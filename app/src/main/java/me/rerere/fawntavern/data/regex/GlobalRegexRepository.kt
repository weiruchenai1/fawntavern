package me.rerere.fawntavern.data.regex

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.JsonFileDir
import me.rerere.fawntavern.data.preset.PresetParser
import me.rerere.fawntavern.data.preset.RegexScript
import org.json.JSONArray
import org.json.JSONObject

/** Persistent scripts that apply to every chat, independent of the active card or preset. */
object GlobalRegexRepository {
    private const val DIR = "regex"
    private const val FILE = "global"
    private val mutex = Mutex()

    fun regexDir(context: Context) = JsonFileDir.dir(context, DIR)

    suspend fun load(context: Context): List<RegexScript> = withContext(Dispatchers.IO) {
        val file = JsonFileDir.file(context, DIR, FILE)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val root = JSONObject(file.readText())
            parseScripts(root.optJSONArray("regex_scripts"))
        }.getOrDefault(emptyList())
    }

    suspend fun save(context: Context, scripts: List<RegexScript>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            JsonFileDir.atomicWriteText(
                JsonFileDir.file(context, DIR, FILE),
                JSONObject().put("regex_scripts", serializeScripts(scripts)).toString(2),
            )
        }
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            regexDir(context).listFiles()?.forEach { it.delete() }
        }
        Unit
    }

    suspend fun parseUri(context: Context, uri: Uri): RegexScript = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            ?: throw IllegalStateException("Unable to read regex script")
        val parsed = PresetParser.parseRegexScript(JSONObject(text))
        if (parsed.id.isBlank()) parsed.copy(id = java.util.UUID.randomUUID().toString()) else parsed
    }

    private fun parseScripts(array: JSONArray?): List<RegexScript> = buildList {
        if (array == null) return@buildList
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let {
                val parsed = PresetParser.parseRegexScript(it)
                add(if (parsed.id.isBlank()) parsed.copy(id = java.util.UUID.randomUUID().toString()) else parsed)
            }
        }
    }

    private fun serializeScripts(scripts: List<RegexScript>): JSONArray =
        JSONArray().apply { scripts.forEach { put(PresetParser.serializeRegexScript(it)) } }
}
