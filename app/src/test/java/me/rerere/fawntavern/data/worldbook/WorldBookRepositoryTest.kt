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

    /** 角色卡内嵌书抽出来的文件是 character_book 形态（entries 为数组、私有字段在 extensions 下） */
    @Test
    fun saveEntriesPatchesCharacterBookShapeWithoutLosingPrivateFields() = runBlocking {
        val file = File(WorldBookRepository.worldDir(context), "Embedded.json")
        file.writeText(
            """
            {"name":"Embedded","entries":[{"id":7,"keys":["old"],"secondary_keys":[],
            "comment":"c","content":"old","constant":false,"selective":false,
            "insertion_order":100,"enabled":true,"position":"before_char","use_regex":true,
            "extensions":{"position":0,"exclude_recursion":true,"case_sensitive":true,
            "automation_id":"keep","triggers":["t"],"ignore_budget":true}}]}
            """.trimIndent()
        )

        val entry = WorldBookRepository.load(context, "Embedded").entries.getValue(7)
        assertEquals(listOf("old"), entry.keys)
        assertEquals(true, entry.excludeRecursion)
        assertEquals(true, entry.caseSensitive)

        WorldBookRepository.saveEntries(
            context,
            "Embedded",
            listOf(
                entry.copy(
                    keys = listOf("new"),
                    content = "new",
                    excludeRecursion = false,
                    caseSensitive = null,
                )
            ),
        )

        // character_book 的同义键会盖掉/取或本次写入的值，保存时必须清掉
        val reloaded = WorldBookRepository.load(context, "Embedded").entries.getValue(7)
        assertEquals(listOf("new"), reloaded.keys)
        assertEquals("new", reloaded.content)
        assertEquals(false, reloaded.excludeRecursion)
        assertEquals(null, reloaded.caseSensitive)
        assertEquals(WorldBookPos.BEFORE_CHAR, reloaded.position)

        // 未建模的 ST 私有字段随原 entry 对象保留
        val ext = JSONObject(file.readText())
            .getJSONObject("entries").getJSONObject("7").getJSONObject("extensions")
        assertEquals("keep", ext.getString("automation_id"))
        assertEquals(true, ext.getBoolean("ignore_budget"))
        assertEquals("t", ext.getJSONArray("triggers").getString(0))
    }
}
