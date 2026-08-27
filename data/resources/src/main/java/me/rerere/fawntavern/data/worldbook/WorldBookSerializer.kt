package me.rerere.fawntavern.data.worldbook

import org.json.JSONArray
import org.json.JSONObject

/**
 * 世界书 → 角色卡 `character_book` 序列化（[WorldBookParser] 的逆过程）。
 * 导出角色卡时按当前关联的世界书重新生成内嵌书：卡内原始 character_book 自导入后就不再
 * 更新，直接导出会丢掉用户在世界书里做的全部编辑。
 * 每条 entry 未建模的字段（automation_id / triggers / ignore_budget / match_* 等）从源文件
 * 原样搬进 extensions，避免往返丢字段。
 */
object WorldBookSerializer {

    /** 本对象自己会写进 extensions 的键；从源 extensions 搬运时先剔除，避免旧值覆盖新值 */
    private val OWNED_EXT_KEYS = setOf(
        "position", "exclude_recursion", "probability", "useProbability", "depth",
        "selectiveLogic", "group", "group_override", "group_weight", "prevent_recursion",
        "delay_until_recursion", "scan_depth", "match_whole_words", "use_group_scoring",
        "case_sensitive", "role", "vectorized", "sticky", "cooldown", "delay", "outletName",
    )

    /** 一本世界书的原始 JSON 与解析结果；raw 只用于捞未建模字段 */
    data class Source(val raw: JSONObject, val book: WorldBook)

    /**
     * 合并多本世界书为一个 character_book。character_book 只装得下一本，而角色卡可以关联多本，
     * 全部合并才不丢内容；id 按合并顺序重排，避免跨书主键相撞。
     */
    fun toCharacterBook(name: String, sources: List<Source>): JSONObject {
        val entries = JSONArray()
        var nextId = 0
        sources.forEach { source ->
            val rawById = rawEntriesById(source.raw)
            source.book.entries.values.forEach { entry ->
                entries.put(entryToJson(entry, rawById[entry.id], nextId))
                nextId++
            }
        }
        return JSONObject().put("name", name).put("entries", entries)
    }

    /**
     * 单条 entry → character_book 形态。顶层只放 spec V2 的标准字段，其余全进 extensions
     * （ST 后端 convertWorldInfoToCharacterBook 即此布局）；顶层 position 只有粗粒度的
     * before/after_char，精确位置靠 extensions.position 的 0-7 枚举表达。
     */
    private fun entryToJson(entry: WorldBookEntry, raw: JSONObject?, id: Int): JSONObject {
        val ext = JSONObject()
        raw?.optJSONObject("extensions")?.let { srcExt ->
            srcExt.keys().forEach { key ->
                if (key !in OWNED_EXT_KEYS) ext.put(key, srcExt.get(key))
            }
        }
        ext.put("position", WorldBookPos.toStId(entry.position))
        ext.put("depth", entry.depth)
        ext.put("role", entry.role)
        ext.put("probability", entry.probability)
        // useProbability=false 会让 ST 忽略概率，概率 <100 时必须显式打开才能还原语义
        ext.put("useProbability", entry.probability < 100)
        ext.put("selectiveLogic", entry.selectiveLogic)
        ext.put("vectorized", entry.vectorized)
        ext.put("exclude_recursion", entry.excludeRecursion)
        ext.put("prevent_recursion", entry.preventRecursion)
        ext.put("delay_until_recursion", entry.delayUntilRecursion)
        ext.put("group", entry.group)
        ext.put("group_override", entry.groupOverride)
        ext.put("group_weight", entry.groupWeight)
        ext.put("use_group_scoring", entry.useGroupScoring)
        ext.put("sticky", entry.sticky)
        ext.put("cooldown", entry.cooldown)
        ext.put("delay", entry.delay)
        // 三个条目级覆盖为 null 表示「跟全局」，不写字段才能还原（写 false 会变成显式关闭）
        entry.scanDepth?.let { ext.put("scan_depth", it) }
        entry.caseSensitive?.let { ext.put("case_sensitive", it) }
        entry.matchWholeWords?.let { ext.put("match_whole_words", it) }
        // outlet 插槽名是本 App 的扩展字段，ST 无对应项，放 extensions 保证自家往返不丢
        if (entry.outletName.isNotBlank()) ext.put("outletName", entry.outletName)
        return JSONObject()
            .put("id", id)
            .put("keys", JSONArray(entry.keys))
            .put("secondary_keys", JSONArray(entry.keySecondary))
            .put("comment", entry.comment)
            .put("content", entry.content)
            .put("constant", entry.constant)
            .put("selective", entry.keySecondary.isNotEmpty())
            .put("insertion_order", entry.insertionOrder)
            .put("enabled", entry.enabled)
            .put(
                "position",
                if (entry.position == WorldBookPos.BEFORE_CHAR) "before_char" else "after_char",
            )
            .put("use_regex", true)
            .put("extensions", ext)
    }

    /** 源文件条目按 id 索引；entries 既可能是 id 为键的对象也可能是数组（character_book 形态） */
    private fun rawEntriesById(raw: JSONObject): Map<Int, JSONObject> {
        val out = mutableMapOf<Int, JSONObject>()
        raw.optJSONObject("entries")?.let { obj ->
            obj.keys().forEach { key ->
                obj.optJSONObject(key)?.let { e ->
                    out[e.optInt("id", e.optInt("uid", key.toIntOrNull() ?: 0))] = e
                }
            }
        }
        raw.optJSONArray("entries")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { e -> out[e.optInt("id", e.optInt("uid", i))] = e }
            }
        }
        return out
    }
}
