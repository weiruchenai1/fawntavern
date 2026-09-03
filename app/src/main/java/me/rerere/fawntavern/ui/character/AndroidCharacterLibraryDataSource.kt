package me.rerere.fawntavern.ui.character

import android.content.Context
import android.net.Uri
import java.io.File
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.chat.ChatRepository
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.regex.RegexSetRepository
import me.rerere.fawntavern.data.worldbook.WorldBookRepository

internal class AndroidCharacterLibraryDataSource(
    private val context: Context,
) : CharacterLibraryDataSource {
    private suspend fun defaultPresetId(): String {
        val name = PresetRepository.ensureDefaultPreset(context, context.getString(R.string.default_preset))
        return PresetRepository.load(context, name).id
    }

    override fun defaultCardName(): String? = CharacterRepository.defaultCardName(context)
    override suspend fun names(): List<String> = CharacterRepository.listNames(context)
    override suspend fun load(name: String): CharacterCard = CharacterRepository.load(context, name)
    override suspend fun create(name: String): CharacterCard =
        CharacterRepository.create(context, name, defaultPresetId())
    override suspend fun import(uri: Uri): CharacterCard =
        CharacterRepository.import(context, uri, defaultPresetId())
    override suspend fun delete(name: String) = delete(name, false, false)
    override suspend fun chatCount(name: String): Int = ChatRepository.countForCharacter(context, name)

    override suspend fun delete(name: String, deleteChats: Boolean, deleteAssociations: Boolean) {
        val card = if (deleteAssociations) {
            runCatching { CharacterRepository.load(context, name) }.getOrNull()
        } else null
        if (deleteChats) ChatRepository.deleteForCharacter(context, name)
        if (deleteAssociations) {
            val referencedBooks = CharacterRepository.referencedWorldBookIds(context, excluding = name)
            card?.enabledWorldBookIds?.asSequence()?.filter(String::isNotBlank)?.distinct()
                ?.filter { it !in referencedBooks }?.forEach { id ->
                    runCatching { WorldBookRepository.loadById(context, id) }.getOrNull()
                        ?.let { CharacterRepository.deleteWorldBook(context, it.name) }
                }
            val referencedRegex = CharacterRepository.referencedRegexIds(context, excluding = name)
            card?.enabledRegexIds?.asSequence()?.filter(String::isNotBlank)?.distinct()
                ?.filter { it !in referencedRegex }?.forEach { id ->
                    val set = runCatching { RegexSetRepository.loadById(context, id) }.getOrNull()
                    if (set != null && !set.global) CharacterRepository.deleteRegexSet(context, set.name)
                }
        }
        CharacterRepository.delete(context, name)
    }

    override suspend fun saveOrder(names: List<String>) = CharacterRepository.saveOrder(context, names)
    override suspend fun exportPng(name: String): ByteArray = CharacterRepository.exportPngBytes(context, name)
    override suspend fun exportJson(name: String): ByteArray = CharacterRepository.exportJsonBytes(context, name)
    override fun imageFile(name: String): File = CharacterRepository.imageFile(context, name)
    override fun thumbnail(name: String) = CharacterRepository.decodeImageThumb(context, name)
}
