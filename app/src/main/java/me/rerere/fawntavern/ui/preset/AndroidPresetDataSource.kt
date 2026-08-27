package me.rerere.fawntavern.ui.preset

import android.content.Context
import android.net.Uri
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.preset.RegexScript
import me.rerere.fawntavern.data.preset.StPreset

internal class AndroidPresetDataSource(
    private val context: Context,
) : PresetDataSource {
    override fun defaultName(): String? = PresetRepository.defaultPresetName(context)
    override suspend fun names(): List<String> {
        PresetRepository.ensureDefaultPreset(context, context.getString(R.string.default_preset))
        return PresetRepository.listNames(context)
    }
    override suspend fun load(name: String): StPreset = PresetRepository.load(context, name)
    override suspend fun create(name: String): StPreset = PresetRepository.create(context, name)
    override suspend fun import(uri: Uri): StPreset = PresetRepository.import(context, uri)
    override suspend fun rename(old: String, new: String): Boolean = PresetRepository.rename(context, old, new)
    override suspend fun delete(name: String) = CharacterRepository.deletePreset(context, name)
    override suspend fun save(preset: StPreset) = PresetRepository.save(context, preset)
    override suspend fun parseRegex(uri: Uri): RegexScript = PresetRepository.parseRegexUri(context, uri)
}
