package me.rerere.fawntavern.ui.worldbook

import android.content.Context
import android.net.Uri
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.data.worldbook.WorldBookEntry
import me.rerere.fawntavern.data.worldbook.WorldBookRepository
import me.rerere.fawntavern.data.character.CharacterRepository

internal interface WorldBookDataSource {
    suspend fun names(): List<String>
    suspend fun load(name: String): WorldBook
    suspend fun create(name: String): WorldBook
    suspend fun import(uri: Uri): WorldBook
    suspend fun rename(old: String, new: String): Boolean
    suspend fun delete(name: String)
    suspend fun saveEntries(name: String, entries: List<WorldBookEntry>)
    suspend fun exportJson(name: String): ByteArray = error("JSON export is not supported")
}

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

internal class WorldBookDataController(
    private val dataSource: WorldBookDataSource,
) {
    constructor(context: Context) : this(AndroidWorldBookDataSource(context))

    suspend fun names(): List<String> = dataSource.names()
    suspend fun load(name: String): WorldBook = dataSource.load(name)
    suspend fun create(name: String): String = dataSource.create(name).name
    suspend fun import(uri: Uri): String = dataSource.import(uri).name
    suspend fun rename(old: String, new: String): Boolean = dataSource.rename(old, new)
    suspend fun delete(name: String) = dataSource.delete(name)
    suspend fun saveEntries(name: String, entries: List<WorldBookEntry>) =
        dataSource.saveEntries(name, entries)
    suspend fun exportJson(name: String): ByteArray = dataSource.exportJson(name)
}
