package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import me.rerere.fawntavern.domain.chat.buildSearchSnippet

class ChatSearchControllerTest {
    @Test
    fun trimsQueriesAndOwnsHistoryMutations() = runBlocking {
        val source = FakeSearchDataSource()
        val controller = ChatSearchController(source)

        val hits = controller.search("char.json", "  fawn  ")
        controller.record("  fawn  ")

        assertEquals("fawn", source.lastQuery)
        assertEquals("fawn", source.history().single())
        assertEquals("hit", hits.single().snippet)
        assertEquals(emptyList<String>(), controller.clearHistory())
    }

    @Test
    fun snippetCentersMatchAndFlattensLines() {
        val content = "01234567890123456789\nneedle followed by enough trailing context for clipping"

        val snippet = buildSearchSnippet(content, "needle")

        assertEquals(true, snippet.startsWith("…"))
        assertEquals(true, snippet.contains("needle"))
        assertEquals(false, snippet.contains('\n'))
    }

    private class FakeSearchDataSource : ChatSearchDataSource {
        private val values = mutableListOf<String>()
        var lastQuery: String? = null

        override fun history(): List<String> = values.toList()
        override fun addHistory(query: String) { if (query.isNotEmpty()) values.add(0, query) }
        override fun removeHistory(query: String) { values.remove(query) }
        override fun clearHistory() { values.clear() }
        override suspend fun search(charFile: String, query: String): List<ChatSearchHit> {
            lastQuery = query
            return listOf(ChatSearchHit("session", "title", "hit"))
        }
    }
}
