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
            // character_book 把私有字段放在 extensions.* 下；native 世界书文件放顶层。两处都认
            val ext = e.optJSONObject("extensions")
            fun bool(name: String, def: Boolean = false) =
                if (e.has(name)) e.optBoolean(name, def) else ext?.optBoolean(name, def) ?: def
            fun int(name: String, def: Int) =
                if (e.has(name) && !e.isNull(name)) e.optInt(name, def)
                else if (ext?.has(name) == true && !ext.isNull(name)) ext.optInt(name, def) else def
            fun str(name: String) =
                (if (e.has(name)) e.optString(name, "") else ext?.optString(name, "") ?: "").trim()
            entries[eId] = WorldBookEntry(
                id = eId,
                // ST 世界书文件用 key/keysecondary，character_book 用 keys/secondary_keys，都认
                keys = strList("keys", "key"),
                comment = e.optString("comment", "").trim(),
                content = e.optString("content", "").trim(),
                // ST 世界书文件用 disable（反义），character_book 用 enabled
                enabled = if (e.has("disable")) !e.optBoolean("disable", false)
                          else e.optBoolean("enabled", true),
                // 顶层 position 为数字 → 权威（native / 本 App 保存）；为粗粒度串（before/after_char）或缺失
                // → 取 extensions.position（character_book v3 的详细枚举 0-7），避免所有条目被误判为 after_char
                position = WorldBookPos.normalize(run {
                    val top = e.opt("position")
                    if (top is Number || (top is String && top.toIntOrNull() != null)) top
                    else ext?.opt("position")?.takeIf { it != JSONObject.NULL } ?: top
                }),
                insertionOrder = e.optInt("insertion_order", e.optInt("order", 100)),
                constant = e.optBoolean("constant", false),
                vectorized = bool("vectorized"),
                depth = int("depth", 4),
                role = int("role", 0).coerceIn(0, 2),
                outletName = str("outletName"),
                keySecondary = strList("keysecondary", "secondary_keys"),
                selectiveLogic = int("selectiveLogic", 0),
                // useProbability=false 表示不掷骰（等效 100%）；两字段 character_book 在 extensions 下、native 在顶层
                probability = if (!bool("useProbability", true)) 100 else int("probability", 100).coerceIn(0, 100),
                scanDepth = if (e.isNull("scanDepth")) null else e.optInt("scanDepth").takeIf { it > 0 }
                    ?: ext?.optInt("scan_depth", -1)?.takeIf { it > 0 },
                caseSensitive = when {
                    e.has("caseSensitive") && !e.isNull("caseSensitive") -> e.optBoolean("caseSensitive")
                    e.has("case_sensitive") && !e.isNull("case_sensitive") -> e.optBoolean("case_sensitive")
                    ext?.has("case_sensitive") == true && !ext.isNull("case_sensitive") -> ext.optBoolean("case_sensitive")
                    else -> null
                },
                matchWholeWords = when {
                    e.has("matchWholeWords") && !e.isNull("matchWholeWords") -> e.optBoolean("matchWholeWords")
                    ext?.has("match_whole_words") == true && !ext.isNull("match_whole_words") -> ext.optBoolean("match_whole_words")
                    else -> null
                },
                excludeRecursion = bool("excludeRecursion") || bool("exclude_recursion"),
                preventRecursion = bool("preventRecursion") || bool("prevent_recursion"),
                delayUntilRecursion = bool("delayUntilRecursion") || bool("delay_until_recursion"),
                group = str("group"),
                groupOverride = bool("groupOverride") || bool("group_override"),
                groupWeight = int("groupWeight", int("group_weight", 100)),
                useGroupScoring = bool("useGroupScoring") || bool("use_group_scoring"),
                sticky = int("sticky", 0).coerceAtLeast(0),
                cooldown = int("cooldown", 0).coerceAtLeast(0),
                delay = int("delay", 0).coerceAtLeast(0),
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
}
