package me.rerere.fawntavern.data.character

import org.json.JSONObject

object CharacterParser {

    fun parse(json: JSONObject, fileName: String = ""): CharacterCard {
        val d = json.optJSONObject("data") ?: json

        val name = d.optString("name", "").ifBlank {
            fileName.removeSuffix(".json")
        }

        val tags = mutableListOf<String>()
        d.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) tags.add(arr.optString(i, ""))
        }

        val altGreetings = mutableListOf<String>()
        d.optJSONArray("alternate_greetings")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.optString(i, "").trim()
                if (g.isNotBlank()) altGreetings.add(g)
            }
        }

        // 解析内嵌世界书条目与关联的世界书名
        val worldBookEntries = mutableListOf<WorldBookEntry>()
        val charaBook = d.optJSONObject("character_book")
        val enabledWorldBooks = mutableListOf<String>()
        charaBook?.optString("name", "")?.trim()?.takeIf { it.isNotBlank() }?.let {
            enabledWorldBooks.add(it)
        }
        charaBook?.optJSONArray("entries")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { entry ->
                    fun strList(name: String): List<String> {
                        val a = entry.optJSONArray(name) ?: return emptyList()
                        return (0 until a.length()).mapNotNull { j -> a.optString(j, "").trim().takeIf { it.isNotBlank() } }
                    }
                    val ext = entry.optJSONObject("extensions")
                    // character_book 规范里 selective = false 表示忽略次级关键词
                    val secondary = if (entry.optBoolean("selective", true)) strList("secondary_keys") else emptyList()
                    val probability = if (ext != null && !ext.optBoolean("useProbability", true)) 100
                                      else ext?.optInt("probability", 100)?.coerceIn(0, 100) ?: 100
                    worldBookEntries.add(
                        WorldBookEntry(
                            id = entry.optInt("id", i),
                            keys = strList("keys"),
                            comment = entry.optString("comment", "").trim(),
                            content = entry.optString("content", "").trim(),
                            enabled = entry.optBoolean("enabled", true),
                            // 顶层 position 为数字才权威；粗粒度串/缺失时取 extensions.position（v3 详细枚举）
                            position = me.rerere.fawntavern.data.worldbook.WorldBookPos.normalize(run {
                                val top = entry.opt("position")
                                if (top is Number || (top is String && top.toIntOrNull() != null)) top
                                else ext?.opt("position")?.takeIf { it != org.json.JSONObject.NULL } ?: top
                            }),
                            insertionOrder = entry.optInt("insertion_order", 100),
                            constant = entry.optBoolean("constant", false),
                            vectorized = entry.optBoolean("vectorized", ext?.optBoolean("vectorized", false) ?: false),
                            depth = ext?.optInt("depth", 4) ?: 4,
                            role = (ext?.optInt("role", 0) ?: 0).coerceIn(0, 2),
                            keySecondary = secondary,
                            selectiveLogic = ext?.optInt("selectiveLogic", 0) ?: 0,
                            probability = probability,
                            caseSensitive = if (entry.has("case_sensitive") && !entry.isNull("case_sensitive"))
                                entry.optBoolean("case_sensitive") else null,
                        )
                    )
                }
            }
        }

        // 编辑器保存的关联世界书列表存在时以它为准（用户可能取消了 character_book 的关联）
        d.optJSONArray("enabled_world_books")?.let { arr ->
            enabledWorldBooks.clear()
            for (i in 0 until arr.length()) {
                val n = arr.optString(i, "").trim()
                if (n.isNotBlank()) enabledWorldBooks.add(n)
            }
        }

        val talkativeness = d.optJSONObject("extensions")?.optDouble("talkativeness")?.toFloat()
            ?: json.optDouble("talkativeness", 0.5).toFloat()

        // 内嵌正则：data.extensions.regex_scripts
        val regexScripts = mutableListOf<CharRegex>()
        d.optJSONObject("extensions")?.optJSONArray("regex_scripts")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { r ->
                    val placement = mutableListOf<Int>()
                    r.optJSONArray("placement")?.let { pa ->
                        for (j in 0 until pa.length()) placement.add(pa.optInt(j))
                    }
                    // 深度：-1 或缺省 = 无限制（null）
                    val minDepthRaw = r.optInt("minDepth", -1)
                    val maxDepthRaw = r.optInt("maxDepth", -1)
                    val trimStrings = mutableListOf<String>()
                    r.optJSONArray("trimStrings")?.let { ta ->
                        for (j in 0 until ta.length()) trimStrings.add(ta.optString(j, "").trim())
                    }
                    // substituteRegex：旧格式为 boolean → 0/1/2 整数；JSONObject.opt("field") 返回原始类型
                    val substituteRegex = when (val v = r.opt("substituteRegex")) {
                        is Boolean -> if (v) 1 else 0
                        is Number -> v.toInt()
                        null -> 0
                        else -> 0
                    }
                    regexScripts.add(
                        CharRegex(
                            id = r.optString("id", ""),
                            scriptName = r.optString("scriptName", ""),
                            findRegex = r.optString("findRegex", ""),
                            replaceString = r.optString("replaceString", ""),
                            disabled = r.optBoolean("disabled", false),
                            runOnEdit = r.optBoolean("runOnEdit", true),
                            placement = placement,
                            markdownOnly = r.optBoolean("markdownOnly", false),
                            promptOnly = r.optBoolean("promptOnly", false),
                            minDepth = minDepthRaw.takeIf { it >= 0 },
                            maxDepth = maxDepthRaw.takeIf { it >= 0 },
                            trimStrings = trimStrings,
                            substituteRegex = substituteRegex,
                        )
                    )
                }
            }
        }

        // 角色注入提示 extensions.depth_prompt {prompt, depth, role}
        val depthPrompt = d.optJSONObject("extensions")?.optJSONObject("depth_prompt")?.let { dp ->
            val prompt = dp.optString("prompt", "").trim()
            if (prompt.isBlank()) null
            else DepthPrompt(
                prompt = prompt,
                depth = dp.optInt("depth", 4),
                role = dp.optString("role", "system").ifBlank { "system" },
            )
        }

        return CharacterCard(
            name = name,
            description = d.optString("description", "").trim(),
            personality = d.optString("personality", "").trim(),
            scenario = d.optString("scenario", "").trim(),
            firstMes = d.optString("first_mes", "").trim(),
            mesExample = d.optString("mes_example", "").trim(),
            talkativeness = talkativeness,
            fav = d.optJSONObject("extensions")?.optBoolean("fav", false)
                ?: json.optBoolean("fav", false),
            tags = tags,
            alternateGreetings = altGreetings,
            creatorNotes = d.optString("creator_notes", "").trim(),
            systemPrompt = d.optString("system_prompt", "").trim(),
            postHistoryInstructions = d.optString("post_history_instructions", "").trim(),
            world = d.optJSONObject("extensions")?.optString("world", "") ?: "",
            worldBookEntries = worldBookEntries,
            enabledWorldBooks = enabledWorldBooks,
            linkedPreset = d.optString("linked_preset", "").trim(),
            linkedRegex = if (d.has("linked_regex")) {
                d.optString("linked_regex", "").trim()
            } else {
                fileName.removeSuffix(".json")
            },
            streaming = d.optBoolean("streaming", true),
            regexScripts = regexScripts,
            depthPrompt = depthPrompt,
        )
    }
}
