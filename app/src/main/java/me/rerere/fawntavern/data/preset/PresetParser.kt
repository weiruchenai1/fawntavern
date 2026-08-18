package me.rerere.fawntavern.data.preset

import org.json.JSONArray
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

        // SillyTavern stores preset-scoped scripts in extensions.regex_scripts.
        // Keep reading the old FawnTavern root field so existing local presets remain usable.
        val regexScripts = parseRegexScripts(
            json.optJSONObject("extensions")?.optJSONArray("regex_scripts")
                ?: json.optJSONArray("regex_scripts")
        )

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
            prompts = orderPromptsBy(prompts, promptOrder),
            promptOrder = promptOrder,
            regexScripts = regexScripts,
        )
    }

    /**
     * 依 prompt_order 重排内容池。ST 里「顺序 + 是否启用」的权威来源是 prompt_order，
     * prompts[] 只是无序内容池、且本身不带 enabled（详见预设设计文档 3.1 关键陷阱）。
     * 选主排序组（characterId==100001 优先，否则首组，与 [me.rerere.fawntavern.domain.PromptBuilder] 一致），
     * 按其顺序排列 prompts 并把每条 toggle 的 enabled 合并进 [PromptItem]；
     * order 未包含的 prompt 视为未启用、按原相对顺序附于末尾。prompt_order 缺失时原样返回。
     */
    private fun orderPromptsBy(
        prompts: List<PromptItem>,
        promptOrder: List<PromptOrderEntry>,
    ): List<PromptItem> {
        val main = promptOrder.firstOrNull { it.characterId == 100001 }
            ?: promptOrder.firstOrNull()
        if (main == null || main.order.isEmpty()) return prompts
        val byId = prompts.associateBy { it.identifier }
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<PromptItem>()
        for (t in main.order) {
            val p = byId[t.identifier] ?: continue
            if (!seen.add(t.identifier)) continue
            result.add(p.copy(enabled = t.enabled))
        }
        for (p in prompts) {
            if (!seen.add(p.identifier)) continue
            result.add(p.copy(enabled = false))
        }
        return result
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
            null -> 0
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

    private fun parseRegexScripts(array: JSONArray?): List<RegexScript> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { add(parseRegexScript(it)) }
            }
        }
    }

    /** 将正则脚本序列化回 ST 兼容 JSON（用于写入预设的 regex_scripts）。 */
    fun serializeRegexScript(s: RegexScript): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("scriptName", s.scriptName)
        put("findRegex", s.findRegex)
        put("replaceString", s.replaceString)
        put("disabled", s.disabled)
        put("placement", JSONArray().also { s.placement.forEach { p -> it.put(p) } })
        put("markdownOnly", s.markdownOnly)
        put("promptOnly", s.promptOnly)
        put("runOnEdit", s.runOnEdit)
        put("minDepth", s.minDepth ?: JSONObject.NULL)
        put("maxDepth", s.maxDepth ?: JSONObject.NULL)
        put("trimStrings", JSONArray().also { s.trimStrings.forEach { t -> it.put(t) } })
        put("substituteRegex", s.substituteRegex)
    }
}
