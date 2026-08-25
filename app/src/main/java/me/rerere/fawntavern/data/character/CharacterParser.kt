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

        val enabledWorldBookIds = mutableListOf<String>()
        d.optJSONArray("enabled_world_book_ids")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.optString(i, "").trim()
                if (n.isNotBlank()) enabledWorldBookIds.add(n)
            }
        }

        val enabledRegexIds = mutableListOf<String>()
        d.optJSONArray("enabled_regex_ids")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.optString(i, "").trim()
                if (n.isNotBlank()) enabledRegexIds.add(n)
            }
        }

        val talkativeness = d.optJSONObject("extensions")?.optDouble("talkativeness")?.toFloat()
            ?: json.optDouble("talkativeness", 0.5).toFloat()

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
            enabledWorldBookIds = enabledWorldBookIds,
            enabledRegexIds = enabledRegexIds,
            linkedPresetId = d.optString("linked_preset_id", "").trim(),
            streaming = d.optBoolean("streaming", true),
            depthPrompt = depthPrompt,
        )
    }
}
