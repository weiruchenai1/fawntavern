package me.rerere.fawntavern.ui.regex

import android.content.Context
import android.net.Uri
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.preset.PresetParser
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.preset.RegexScript
import me.rerere.fawntavern.data.regex.GlobalRegexRepository
import me.rerere.fawntavern.data.regex.RegexSetRepository

internal class AndroidRegexLibraryDataSource(
    private val context: Context,
) : RegexLibraryDataSource {
    override suspend fun load(): RegexCatalog {
        val regexSets = RegexSetRepository.loadAll(context)
        val presets = PresetRepository.listNames(context).mapNotNull { name ->
            runCatching { RegexGroup(name, name, PresetRepository.load(context, name).regexScripts) }.getOrNull()
        }
        return RegexCatalog(
            global = regexSets.filter { it.global }.map { RegexGroup(it.name, it.name, it.scripts) },
            preset = presets,
            local = regexSets.filterNot { it.global }.map { RegexGroup(it.name, it.name, it.scripts) },
        )
    }

    override suspend fun append(source: RegexSource, additions: List<RegexScript>) {
        when (source.scope) {
            RegexScope.GLOBAL, RegexScope.LOCAL -> additions.forEach {
                RegexSetRepository.appendScript(context, source.name, it)
            }
            RegexScope.PRESET -> {
                val preset = PresetRepository.load(context, source.name)
                PresetRepository.save(context, preset.copy(regexScripts = preset.regexScripts + additions))
            }
        }
    }

    override suspend fun update(
        source: RegexSource,
        index: Int,
        script: RegexScript,
        scripts: List<RegexScript>,
    ) {
        when (source.scope) {
            RegexScope.GLOBAL, RegexScope.LOCAL -> RegexSetRepository.updateScript(context, source.name, index, script)
            RegexScope.PRESET -> {
                val preset = PresetRepository.load(context, source.name)
                PresetRepository.save(context, preset.copy(regexScripts = scripts))
            }
        }
    }

    override suspend fun deleteScript(source: RegexSource, index: Int, scripts: List<RegexScript>) {
        when (source.scope) {
            RegexScope.GLOBAL, RegexScope.LOCAL -> RegexSetRepository.deleteScript(context, source.name, index)
            RegexScope.PRESET -> {
                val preset = PresetRepository.load(context, source.name)
                PresetRepository.save(context, preset.copy(regexScripts = scripts))
            }
        }
    }

    override suspend fun importScript(uri: Uri): RegexScript = GlobalRegexRepository.parseUri(context, uri)

    override fun serialize(script: RegexScript): ByteArray =
        PresetParser.serializeRegexScript(script).toString(2).toByteArray()

    override fun defaultPresetName(): String = PresetRepository.defaultPresetName(context).orEmpty()

    override suspend fun create(source: RegexSource, name: String) {
        when (source.scope) {
            RegexScope.GLOBAL -> RegexSetRepository.create(context, name, global = true)
            RegexScope.PRESET -> PresetRepository.create(context, name)
            RegexScope.LOCAL -> RegexSetRepository.create(context, name)
        }
    }

    override suspend fun rename(source: RegexSource, name: String): Boolean = when (source.scope) {
        RegexScope.GLOBAL, RegexScope.LOCAL -> CharacterRepository.renameRegexSet(context, source.name, name)
        RegexScope.PRESET -> PresetRepository.rename(context, source.name, name)
    }

    override suspend fun delete(source: RegexSource) {
        when (source.scope) {
            RegexScope.GLOBAL, RegexScope.LOCAL -> CharacterRepository.deleteRegexSet(context, source.name)
            RegexScope.PRESET -> CharacterRepository.deletePreset(context, source.name)
        }
    }
}
