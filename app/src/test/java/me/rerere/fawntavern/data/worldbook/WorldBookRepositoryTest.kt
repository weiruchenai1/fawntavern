package me.rerere.fawntavern.data.worldbook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorldBookRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorldBookRepository.worldDir(context).deleteRecursively()
    }

    @Test
    fun saveFindsFileWhenJsonNameDiffersFromFileName() = runBlocking {
        val file = File(WorldBookRepository.worldDir(context), "Blue.Archive.json")
        file.writeText(
            """
            {"name":"Kivotos","entries":{"1":{"uid":1,"id":1,"key":["Kivotos"],
            "comment":"City","content":"old","order":100,"position":0,"disable":false,
            "automationId":"keep-me"}}}
            """.trimIndent()
        )

        val book = WorldBookRepository.load(context, "Blue.Archive")
        assertEquals("Blue.Archive", book.name)

        WorldBookRepository.saveEntries(
            context,
            book.name,
            book.entries.values.map { it.copy(content = "new") },
        )

        val saved = JSONObject(file.readText())
        val entry = saved.getJSONObject("entries").getJSONObject("1")
        assertEquals("new", entry.getString("content"))
        assertEquals("Kivotos", saved.getString("name"))
        assertEquals("keep-me", entry.getString("automationId"))
        assertEquals("new", WorldBookRepository.load(context, "Blue.Archive").entries.getValue(1).content)
    }

    @Test
    fun fallsBackToJsonNameWithoutFileName() {
        val json = JSONObject("""{"name":"Kivotos","entries":{}}""")

        assertEquals("Kivotos", WorldBookParser.parse(json).name)
    }

    @Test
    fun createWritesEmptyBookAndKeepsExistingOneOnNameClash() = runBlocking {
        val first = WorldBookRepository.create(context, "Kivotos")
        assertEquals("Kivotos", first.name)
        assertEquals(emptyMap<Int, WorldBookEntry>(), WorldBookRepository.load(context, first.name).entries)

        val entry = WorldBookEntry(id = 0, keys = listOf("Kivotos"), comment = "City", content = "text")
        WorldBookRepository.saveEntries(context, first.name, listOf(entry))

        // 同名再建走去重后缀，原世界书内容不受影响
        val second = WorldBookRepository.create(context, "Kivotos")
        assertEquals("Kivotos (2)", second.name)
        assertEquals("text", WorldBookRepository.load(context, first.name).entries.getValue(0).content)
        assertEquals(emptyMap<Int, WorldBookEntry>(), WorldBookRepository.load(context, second.name).entries)
    }
}
