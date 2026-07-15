package me.rerere.stapp.data.preset

import org.json.JSONObject

/**
 * 将 SillyTavern 预设 JSON 解析为 [StPreset]。
 */
object PresetParser {

    fun parse(json: JSONObject, fileName: String = ""): StPreset {
        val prompts = mutableListOf<PromptItem>()
        json.optJSONArray("prompts")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { obj ->
                    prompts.add(
                        PromptItem(
                            identifier = obj.optString("identifier", ""),
                            name = obj.optString("name", ""),
                            content = obj.optString("content", ""),
                            role = obj.optString("role", "system"),
                            systemPrompt = obj.optBoolean("system_prompt", false),
                            marker = obj.optBoolean("marker", false),
                            enabled = obj.optBoolean("enabled", true),
                            injectionPosition = obj.optInt("injection_position", 0),
                            injectionDepth = obj.optInt("injection_depth", 4),
                            forbidOverrides = obj.optBoolean("forbid_overrides", false),
                        )
                    )
                }
            }
        }

        val promptOrder = mutableListOf<PromptOrderEntry>()
        json.optJSONArray("prompt_order")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { obj ->
                    val toggles = mutableListOf<PromptToggle>()
                    obj.optJSONArray("order")?.let { orderArr ->
                        for (j in 0 until orderArr.length()) {
                            orderArr.optJSONObject(j)?.let { t ->
                                toggles.add(
                                    PromptToggle(
                                        identifier = t.optString("identifier", ""),
                                        enabled = t.optBoolean("enabled", true),
                                    )
                                )
                            }
                        }
                    }
                    promptOrder.add(
                        PromptOrderEntry(
                            characterId = obj.optInt("character_id", 100001),
                            order = toggles,
                        )
                    )
                }
            }
        }

        // 根据对应的 source 字段推导当前使用的模型名
        val source = json.optString("chat_completion_source", "openai")
        val modelName = when (source) {
            "openai" -> json.optString("openai_model", "")
            "claude" -> json.optString("claude_model", "")
            "makersuite", "google" -> json.optString("google_model", "")
            "custom" -> json.optString("custom_model", "")
            "openrouter" -> json.optString("openrouter_model", "")
            else -> json.optString("custom_model", "")
        }

        return StPreset(
            name = fileName.removeSuffix(".json").ifBlank { json.optString("name", "") },
            chatCompletionSource = source,
            openaiModel = json.optString("openai_model", ""),
            claudeModel = json.optString("claude_model", ""),
            googleModel = json.optString("google_model", ""),
            customModel = json.optString("custom_model", ""),
            openrouterModel = json.optString("openrouter_model", ""),
            temperature = json.optDouble("temperature", 1.0).toFloat(),
            frequencyPenalty = json.optDouble("frequency_penalty", 0.0).toFloat(),
            presencePenalty = json.optDouble("presence_penalty", 0.0).toFloat(),
            topP = json.optDouble("top_p", 1.0).toFloat(),
            topK = json.optInt("top_k", 0),
            topA = json.optDouble("top_a", 1.0).toFloat(),
            minP = json.optDouble("min_p", 0.0).toFloat(),
            repetitionPenalty = json.optDouble("repetition_penalty", 1.0).toFloat(),
            seed = json.optInt("seed", -1),
            maxContext = json.optInt("openai_max_context", 80000),
            maxTokens = json.optInt("openai_max_tokens", 4096),
            maxContextUnlocked = json.optBoolean("max_context_unlocked", true),
            streamOpenai = json.optBoolean("stream_openai", true),
            bypassStatusCheck = json.optBoolean("bypass_status_check", false),
            functionCalling = json.optBoolean("function_calling", false),
            prompts = prompts,
            promptOrder = promptOrder,
        )
    }

    /** 解析 ST 正则脚本 JSON 文件。 */
    fun parseRegexScript(json: JSONObject): RegexScript {
        val placement = mutableListOf<Int>()
        json.optJSONArray("placement")?.let { arr ->
            for (i in 0 until arr.length()) placement.add(arr.optInt(i, 2))
        }
        val trimStrings = mutableListOf<String>()
        json.optJSONArray("trimStrings")?.let { arr ->
            for (i in 0 until arr.length()) trimStrings.add(arr.optString(i, ""))
        }
        // substituteRegex: 旧格式为 boolean，新格式为 0/1/2
        val substituteRegex = when (val v = json.opt("substituteRegex")) {
            is Boolean -> if (v) 1 else 0
            is Number -> v.toInt()
            else -> 0
        }
        return RegexScript(
            id = json.optString("id", ""),
            scriptName = json.optString("scriptName", ""),
            findRegex = json.optString("findRegex", ""),
            replaceString = json.optString("replaceString", ""),
            disabled = json.optBoolean("disabled", false),
            placement = placement,
            markdownOnly = json.optBoolean("markdownOnly", true),
            promptOnly = json.optBoolean("promptOnly", true),
            runOnEdit = json.optBoolean("runOnEdit", true),
            minDepth = if (json.isNull("minDepth")) null else json.optInt("minDepth").takeIf { it >= 0 },
            maxDepth = if (json.isNull("maxDepth")) null else json.optInt("maxDepth").takeIf { it >= 0 },
            trimStrings = trimStrings,
            substituteRegex = substituteRegex,
        )
    }
}
