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

        // 内嵌 character_book 只取书名做关联兜底：条目本身不参与激活（见 PromptBuilder），
        // 导入与迁移都会把它抽成独立世界书文件并写下 enabled_world_books
        val enabledWorldBooks = mutableListOf<String>()
        d.optJSONObject("character_book")
            ?.optString("name", "")?.trim()?.takeIf { it.isNotBlank() }
            ?.let { enabledWorldBooks.add(it) }

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
