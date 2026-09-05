package me.rerere.fawntavern.ui.worldbook

import android.net.Uri
import me.rerere.fawntavern.core.resource.ImportableResourceController
import me.rerere.fawntavern.core.resource.NamedResourceCreator
import me.rerere.fawntavern.core.resource.NamedResourceReader
import me.rerere.fawntavern.core.resource.ResourceDeleter
import me.rerere.fawntavern.core.resource.ResourceImporter
import me.rerere.fawntavern.core.resource.ResourceRenamer
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.data.worldbook.WorldBookEntry

interface WorldBookDataSource :
    NamedResourceReader<WorldBook>,
    NamedResourceCreator<WorldBook>,
    ResourceImporter<WorldBook, Uri>,
    ResourceRenamer<String>,
    ResourceDeleter<String> {
    suspend fun saveEntries(name: String, entries: List<WorldBookEntry>)
    suspend fun exportJson(name: String): ByteArray = error("JSON export is not supported")
}

class WorldBookDataController(
    private val dataSource: WorldBookDataSource,
) : ImportableResourceController<WorldBook, Uri> {
    override suspend fun names(): List<String> = dataSource.names()
    override suspend fun load(name: String): WorldBook = dataSource.load(name)
    suspend fun create(name: String): String = dataSource.create(name).name
    override suspend fun import(source: Uri): String = dataSource.import(source).name
    override suspend fun rename(old: String, new: String): Boolean = dataSource.rename(old, new)
    override suspend fun delete(name: String) = dataSource.delete(name)
    suspend fun saveEntries(name: String, entries: List<WorldBookEntry>) =
        dataSource.saveEntries(name, entries)
    suspend fun exportJson(name: String): ByteArray = dataSource.exportJson(name)
}
