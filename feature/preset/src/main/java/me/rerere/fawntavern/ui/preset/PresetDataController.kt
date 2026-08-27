package me.rerere.fawntavern.ui.preset

import android.net.Uri
import me.rerere.fawntavern.data.preset.RegexScript
import me.rerere.fawntavern.data.preset.StPreset

interface PresetDataSource {
    fun defaultName(): String?
    suspend fun names(): List<String>
    suspend fun load(name: String): StPreset
    suspend fun create(name: String): StPreset
    suspend fun import(uri: Uri): StPreset
    suspend fun rename(old: String, new: String): Boolean
    suspend fun delete(name: String)
    suspend fun save(preset: StPreset)
    suspend fun parseRegex(uri: Uri): RegexScript
}

class PresetDataController(
    private val dataSource: PresetDataSource,
) {
    fun isDefault(name: String): Boolean = name == dataSource.defaultName()
    suspend fun names(): List<String> = dataSource.names()
    suspend fun load(name: String): StPreset = dataSource.load(name)
    suspend fun create(name: String): StPreset = dataSource.create(name)
    suspend fun import(uri: Uri): String = dataSource.import(uri).name
    suspend fun rename(old: String, new: String): Boolean = dataSource.rename(old, new)
    suspend fun delete(name: String) = dataSource.delete(name)
    suspend fun save(preset: StPreset) = dataSource.save(preset)
    suspend fun parseRegex(uri: Uri): RegexScript = dataSource.parseRegex(uri)
}
