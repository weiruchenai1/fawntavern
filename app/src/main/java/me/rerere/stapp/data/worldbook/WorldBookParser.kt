package me.rerere.stapp.data.worldbook

import org.json.JSONArray
import org.json.JSONObject

object WorldBookParser {
    fun parse(json: JSONObject, fileName: String = ""): WorldBook {
        val name = json.optString("name", "").ifBlank {
            fileName.removeSuffix(".json")
        }
        val entries = mutableMapOf<Int, WorldBookEntry>()

        fun parseEntry(e: JSONObject, fallbackId: Int) {
            fun strList(vararg names: String): List<String> {
                val arr = names.firstNotNullOfOrNull { e.optJSONArray(it) } ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i -> arr.optString(i, "").trim().takeIf { it.isNotBlank() } }
            }
            val eId = e.optInt("id", e.optInt("uid", fallbackId))
            // ST 的 useProbability = false 表示不掷骰（等效 100%）
            val probability = if (!e.optBoolean("useProbability", true)) 100
                              else e.optInt("probability", 100).coerceIn(0, 100)
            entries[eId] = WorldBookEntry(
                id = eId,
                // ST 世界书文件用 key/keysecondary，character_book 用 keys/secondary_keys，都认
                keys = strList("keys", "key"),
                comment = e.optString("comment", "").trim(),
                content = e.optString("content", "").trim(),
                // ST 世界书文件用 disable（反义），character_book 用 enabled
                enabled = if (e.has("disable")) !e.optBoolean("disable", false)
                          else e.optBoolean("enabled", true),
                position = normalizePosition(e.opt("position")),
                insertionOrder = e.optInt("insertion_order", e.optInt("order", 100)),
                constant = e.optBoolean("constant", false),
                depth = e.optInt("depth", 4),
                keySecondary = strList("keysecondary", "secondary_keys"),
                selectiveLogic = e.optInt("selectiveLogic", 0),
                probability = probability,
                scanDepth = if (e.isNull("scanDepth")) null else e.optInt("scanDepth").takeIf { it > 0 },
                caseSensitive = when {
                    e.has("caseSensitive") && !e.isNull("caseSensitive") -> e.optBoolean("caseSensitive")
                    e.has("case_sensitive") && !e.isNull("case_sensitive") -> e.optBoolean("case_sensitive")
                    else -> null
                },
                matchWholeWords = if (e.has("matchWholeWords") && !e.isNull("matchWholeWords"))
                    e.optBoolean("matchWholeWords") else null,
            )
        }

        // entries 可能是 JSONObject（以 id 为键）或 JSONArray
        val entriesObj = json.optJSONObject("entries")
        val entriesArr = json.optJSONArray("entries")
        if (entriesObj != null) {
            entriesObj.keys().forEach { key ->
                entriesObj.optJSONObject(key)?.let { parseEntry(it, key.toIntOrNull() ?: 0) }
            }
        } else if (entriesArr != null) {
            for (i in 0 until entriesArr.length()) {
                entriesArr.optJSONObject(i)?.let { parseEntry(it, i) }
            }
        }
        return WorldBook(name = name, entries = entries)
    }

    /**
     * 归一化条目位置。ST 世界书文件的 position 是数字
     * （0=角色定义前 1=角色定义后 2/3=作者注释前后 4=@Depth 5/6=示例对话前后），
     * character_book 是字符串（before_char / after_char）。
     * 简化为三类：before_char / after_char / at_depth。
     */
    private fun normalizePosition(raw: Any?): String = when (raw) {
        is Number -> when (raw.toInt()) {
            0 -> "before_char"
            4 -> "at_depth"
            else -> "after_char"
        }
        is String -> when (raw) {
            "before_char", "0" -> "before_char"
            "at_depth", "4" -> "at_depth"
            else -> "after_char"
        }
        else -> "after_char"
    }
}
