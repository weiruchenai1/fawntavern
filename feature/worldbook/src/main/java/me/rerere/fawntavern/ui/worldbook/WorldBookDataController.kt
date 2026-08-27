package me.rerere.fawntavern.ui.worldbook

import android.net.Uri
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.data.worldbook.WorldBookEntry

interface WorldBookDataSource {
    suspend fun names(): List<String>
    suspend fun load(name: String): WorldBook
    suspend fun create(name: String): WorldBook
    suspend fun import(uri: Uri): WorldBook
    suspend fun rename(old: String, new: String): Boolean
    suspend fun delete(name: String)
    suspend fun saveEntries(name: String, entries: List<WorldBookEntry>)
    suspend fun exportJson(name: String): ByteArray = error("JSON export is not supported")
}

class WorldBookDataController(
    private val dataSource: WorldBookDataSource,
) {
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
