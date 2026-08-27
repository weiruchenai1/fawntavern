package me.rerere.fawntavern.ui.worldbook

import android.content.Context
import android.net.Uri
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.data.worldbook.WorldBookEntry
import me.rerere.fawntavern.data.worldbook.WorldBookRepository

internal class AndroidWorldBookDataSource(
    private val context: Context,
) : WorldBookDataSource {
    override suspend fun names(): List<String> = WorldBookRepository.listNames(context)
    override suspend fun load(name: String): WorldBook = WorldBookRepository.load(context, name)
    override suspend fun create(name: String): WorldBook = WorldBookRepository.create(context, name)
    override suspend fun import(uri: Uri): WorldBook = WorldBookRepository.import(context, uri)
    override suspend fun rename(old: String, new: String): Boolean = WorldBookRepository.rename(context, old, new)
    override suspend fun delete(name: String) = CharacterRepository.deleteWorldBook(context, name)
    override suspend fun saveEntries(name: String, entries: List<WorldBookEntry>) =
        WorldBookRepository.saveEntries(context, name, entries)
    override suspend fun exportJson(name: String): ByteArray =
        WorldBookRepository.exportJsonBytes(context, name)
}
