package me.rerere.fawntavern.ui.regex

import android.net.Uri
import me.rerere.fawntavern.data.preset.RegexScript

enum class RegexScope { GLOBAL, PRESET, LOCAL }

data class RegexSource(val scope: RegexScope, val name: String = "")

data class RegexGroup(
    val name: String,
    val displayName: String,
    val scripts: List<RegexScript>,
)

data class RegexCatalog(
    val global: List<RegexGroup>,
    val preset: List<RegexGroup>,
    val local: List<RegexGroup>,
)

interface RegexLibraryDataSource {
    suspend fun load(): RegexCatalog
    suspend fun append(source: RegexSource, additions: List<RegexScript>)
    suspend fun update(source: RegexSource, index: Int, script: RegexScript, scripts: List<RegexScript>)
    suspend fun deleteScript(source: RegexSource, index: Int, scripts: List<RegexScript>)
    suspend fun importScript(uri: Uri): RegexScript
    fun serialize(script: RegexScript): ByteArray
    fun defaultPresetName(): String
    suspend fun create(source: RegexSource, name: String)
    suspend fun rename(source: RegexSource, name: String): Boolean
    suspend fun delete(source: RegexSource)
}

class RegexLibraryController(
    private val dataSource: RegexLibraryDataSource,
) {
    suspend fun load(): RegexCatalog = dataSource.load()
    suspend fun append(source: RegexSource, additions: List<RegexScript>) = dataSource.append(source, additions)
    suspend fun update(source: RegexSource, index: Int, script: RegexScript, scripts: List<RegexScript>) =
        dataSource.update(source, index, script, scripts)
    suspend fun deleteScript(source: RegexSource, index: Int, scripts: List<RegexScript>) =
        dataSource.deleteScript(source, index, scripts)
    suspend fun importScript(uri: Uri): RegexScript = dataSource.importScript(uri)
    fun serialize(script: RegexScript): ByteArray = dataSource.serialize(script)
    fun defaultPresetName(): String = dataSource.defaultPresetName()
    suspend fun create(source: RegexSource, name: String) = dataSource.create(source, name.trim())
    suspend fun rename(source: RegexSource, name: String): Boolean = dataSource.rename(source, name.trim())
    suspend fun delete(source: RegexSource) = dataSource.delete(source)
}
