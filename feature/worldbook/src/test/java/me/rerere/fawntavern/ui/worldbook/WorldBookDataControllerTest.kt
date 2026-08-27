package me.rerere.fawntavern.ui.worldbook

import android.net.Uri
import kotlinx.coroutines.runBlocking
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.data.worldbook.WorldBookEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldBookDataControllerTest {
    @Test
    fun forwardsListLoadEntrySaveRenameAndDelete() = runBlocking {
        val source = FakeWorldBookDataSource()
        val controller = WorldBookDataController(source)
        val entry = WorldBookEntry(id = 1, keys = listOf("key"), comment = "entry", content = "content")

        assertEquals(listOf("book"), controller.names())
        assertEquals(WorldBook(name = "book"), controller.load("book"))
        controller.saveEntries("book", listOf(entry))
        controller.rename("book", "renamed")
        controller.delete("renamed")

        assertEquals("book" to listOf(entry), source.savedEntries)
        assertEquals("book" to "renamed", source.renamed)
        assertEquals("renamed", source.deleted)
    }

    @Test
    fun createReturnsStoredName() = runBlocking {
        val source = FakeWorldBookDataSource()
        val controller = WorldBookDataController(source)

        // 落盘名可能被去重加序号，返回的是实际文件名而非请求名
        assertEquals("new book (2)", controller.create("new book"))
        assertEquals("new book", source.created)
    }

    private class FakeWorldBookDataSource : WorldBookDataSource {
        var savedEntries: Pair<String, List<WorldBookEntry>>? = null
        var renamed: Pair<String, String>? = null
        var deleted: String? = null
        var created: String? = null

        override suspend fun names(): List<String> = listOf("book")
        override suspend fun load(name: String): WorldBook = WorldBook(name = name)
        override suspend fun create(name: String): WorldBook {
            created = name
            return WorldBook(name = "$name (2)")
        }
        override suspend fun import(uri: Uri): WorldBook = WorldBook(name = "imported")
        override suspend fun rename(old: String, new: String): Boolean {
            renamed = old to new
            return true
        }
        override suspend fun delete(name: String) { deleted = name }
        override suspend fun saveEntries(name: String, entries: List<WorldBookEntry>) {
            savedEntries = name to entries
        }
    }
}
