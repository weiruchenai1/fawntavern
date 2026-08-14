package me.rerere.fawntavern.ui.preset

import android.content.Context
import android.net.Uri
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.preset.RegexScript
import me.rerere.fawntavern.data.preset.StPreset

internal interface PresetDataSource {
    suspend fun names(): List<String>
    suspend fun load(name: String): StPreset
    suspend fun import(uri: Uri): StPreset
    suspend fun rename(old: String, new: String): Boolean
    suspend fun delete(name: String)
    suspend fun save(preset: StPreset)
    suspend fun parseRegex(uri: Uri): RegexScript
}

internal class AndroidPresetDataSource(
    private val context: Context,
) : PresetDataSource {
    override suspend fun names(): List<String> = PresetRepository.listNames(context)
    override suspend fun load(name: String): StPreset = PresetRepository.load(context, name)
    override suspend fun import(uri: Uri): StPreset = PresetRepository.import(context, uri)
    override suspend fun rename(old: String, new: String): Boolean = PresetRepository.rename(context, old, new)
    override suspend fun delete(name: String) = PresetRepository.delete(context, name)
    override suspend fun save(preset: StPreset) = PresetRepository.save(context, preset)
    override suspend fun parseRegex(uri: Uri): RegexScript = PresetRepository.parseRegexUri(context, uri)
}

internal class PresetDataController(
    private val dataSource: PresetDataSource,
) {
    constructor(context: Context) : this(AndroidPresetDataSource(context))

    suspend fun names(): List<String> = dataSource.names()
    suspend fun load(name: String): StPreset = dataSource.load(name)
    suspend fun import(uri: Uri): String = dataSource.import(uri).name
    suspend fun rename(old: String, new: String): Boolean = dataSource.rename(old, new)
    suspend fun delete(name: String) = dataSource.delete(name)
    suspend fun save(preset: StPreset) = dataSource.save(preset)
    suspend fun parseRegex(uri: Uri): RegexScript = dataSource.parseRegex(uri)
}
