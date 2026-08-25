package me.rerere.fawntavern.data.worldbook

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldBookSerializerTest {

    /** 导出角色卡时要把关联世界书写回 character_book，建模字段与 ST 私有字段都不能丢 */
    @Test
    fun characterBookRoundTripKeepsModeledAndPrivateFields() {
        val raw = JSONObject(
            """
            {"name":"Kivotos","entries":{"3":{"uid":3,"id":3,"key":["Abydos"],
            "keysecondary":["desert"],"comment":"place","content":"sand","order":42,
            "position":4,"depth":2,"role":1,"disable":false,"constant":false,
            "probability":30,"useProbability":true,"selectiveLogic":2,
            "excludeRecursion":true,"preventRecursion":true,"group":"g","groupWeight":80,
            "sticky":3,"cooldown":2,"delay":1,"scanDepth":5,"caseSensitive":true,
            "extensions":{"automation_id":"keep","triggers":["t"]}}}}
            """.trimIndent()
        )
        val book = WorldBookParser.parse(raw, "Kivotos")

        val charaBook = WorldBookSerializer.toCharacterBook(
            "Kivotos",
            listOf(WorldBookSerializer.Source(raw, book)),
        )

        // 顶层只放 spec V2 标准字段，粗粒度位置串之外的精确位置靠 extensions.position 表达
        val entry = charaBook.getJSONArray("entries").getJSONObject(0)
        assertEquals("after_char", entry.getString("position"))
        assertEquals(true, entry.getBoolean("selective"))
        assertEquals(listOf("Abydos"), listOf(entry.getJSONArray("keys").getString(0)))
        val ext = entry.getJSONObject("extensions")
        assertEquals(4, ext.getInt("position"))
        // 未建模的 ST 私有字段从源文件原样搬过来
        assertEquals("keep", ext.getString("automation_id"))
        assertEquals("t", ext.getJSONArray("triggers").getString(0))

        val back = WorldBookParser.parse(charaBook, "Kivotos").entries.values.single()
        assertEquals(listOf("Abydos"), back.keys)
        assertEquals(listOf("desert"), back.keySecondary)
        assertEquals("sand", back.content)
        assertEquals(42, back.insertionOrder)
        assertEquals(WorldBookPos.AT_DEPTH, back.position)
        assertEquals(2, back.depth)
        assertEquals(1, back.role)
        assertEquals(30, back.probability)
        assertEquals(2, back.selectiveLogic)
        assertEquals(true, back.excludeRecursion)
        assertEquals(true, back.preventRecursion)
        assertEquals("g", back.group)
        assertEquals(80, back.groupWeight)
        assertEquals(3, back.sticky)
        assertEquals(2, back.cooldown)
        assertEquals(1, back.delay)
        assertEquals(5, back.scanDepth)
        assertEquals(true, back.caseSensitive)
        assertEquals(null, back.matchWholeWords)
    }

    /** 角色卡只能内嵌一本书，多本关联合并时 id 必须重排，否则跨书主键相撞会互相顶掉 */
    @Test
    fun mergesMultipleBooksWithReindexedIds() {
        val first = JSONObject("""{"entries":{"1":{"id":1,"key":["a"],"content":"A"}}}""")
        val second = JSONObject("""{"entries":{"1":{"id":1,"key":["b"],"content":"B"}}}""")

        val merged = WorldBookSerializer.toCharacterBook(
            "Merged",
            listOf(
                WorldBookSerializer.Source(first, WorldBookParser.parse(first, "First")),
                WorldBookSerializer.Source(second, WorldBookParser.parse(second, "Second")),
            ),
        )

        assertEquals("Merged", merged.getString("name"))
        val entries = merged.getJSONArray("entries")
        assertEquals(2, entries.length())
        assertEquals(0, entries.getJSONObject(0).getInt("id"))
        assertEquals(1, entries.getJSONObject(1).getInt("id"))
        assertEquals(2, WorldBookParser.parse(merged, "Merged").entries.size)
    }
}
