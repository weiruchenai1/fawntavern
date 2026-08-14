package me.rerere.fawntavern.data.preset

/**
 * 预设的顶层数据。
 */
data class StPreset(
    val name: String = "",
    val chatCompletionSource: String = "openai",
    val openaiModel: String = "",
    val claudeModel: String = "",
    val googleModel: String = "",
    val customModel: String = "",
    val openrouterModel: String = "",
    val temperature: Float = 1f,
    val frequencyPenalty: Float = 0f,
    val presencePenalty: Float = 0f,
    val topP: Float = 1f,
    val topK: Int = 0,
    val topA: Float = 1f,
    val minP: Float = 0f,
    val repetitionPenalty: Float = 1f,
    val seed: Int = -1,
    val maxContext: Int = 80000,
    val maxTokens: Int = 32768,
    val maxContextUnlocked: Boolean = true,
    val streamOpenai: Boolean = true,
    val bypassStatusCheck: Boolean = false,
    val functionCalling: Boolean = false,
    val prompts: List<PromptItem> = emptyList(),
    val promptOrder: List<PromptOrderEntry> = emptyList(),
    // 预设私有的正则脚本（存进预设 JSON 的 regex_scripts）——只在关联该预设的聊天里生效
    val regexScripts: List<RegexScript> = emptyList(),
)

data class PromptItem(
    val identifier: String,
    val name: String = "",
    val content: String = "",
    val role: String = "system",          // system / user / assistant
    val systemPrompt: Boolean = false,
    val marker: Boolean = false,
    val enabled: Boolean = true,
    val injectionPosition: Int = 0,       // 0 = 随 promptOrder 常规编排，1 = 按 injectionDepth 深度注入
    val injectionDepth: Int = 4,
    val forbidOverrides: Boolean = false,
)

data class PromptOrderEntry(
    val characterId: Int = 100001,
    val order: List<PromptToggle> = emptyList(),
)

data class PromptToggle(
    val identifier: String,
    val enabled: Boolean,
)

data class RegexScript(
    val id: String = "",
    val scriptName: String = "",
    val findRegex: String = "",
    val replaceString: String = "",
    val disabled: Boolean = false,
    val placement: List<Int> = listOf(2),
    val markdownOnly: Boolean = true,
    val promptOnly: Boolean = true,
    val runOnEdit: Boolean = true,
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
    val trimStrings: List<String> = emptyList(),
    val substituteRegex: Int = 0,
)

/** 转成正则引擎的统一脚本类型（RegexEngine 只认 CharRegex） */
fun RegexScript.toCharRegex() = me.rerere.fawntavern.data.character.CharRegex(
    scriptName = scriptName,
    findRegex = findRegex,
    replaceString = replaceString,
    disabled = disabled,
    placement = placement,
    markdownOnly = markdownOnly,
    promptOnly = promptOnly,
    minDepth = minDepth,
    maxDepth = maxDepth,
    trimStrings = trimStrings,
    substituteRegex = substituteRegex,
)
