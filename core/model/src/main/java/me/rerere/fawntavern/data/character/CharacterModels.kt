package me.rerere.fawntavern.data.character

data class CharacterCard(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMes: String = "",
    val mesExample: String = "",
    val talkativeness: Float = 0.5f,
    val fav: Boolean = false,
    val tags: List<String> = emptyList(),
    val alternateGreetings: List<String> = emptyList(),
    val creatorNotes: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val enabledWorldBookIds: List<String> = emptyList(),
    val enabledRegexIds: List<String> = emptyList(),
    val linkedPresetId: String = "",
    val streaming: Boolean = true,  // 流式输出开关
    val depthPrompt: DepthPrompt? = null,  // 角色注入提示（extensions.depth_prompt / Character's Note）
)

/** 角色注入提示：按深度插入聊天历史。 */
data class DepthPrompt(
    val prompt: String = "",
    val depth: Int = 4,
    val role: String = "system",  // system / user / assistant
)

/** 正则引擎统一使用的脚本模型（SillyTavern regex_scripts 子集）。 */
data class CharRegex(
    val id: String = "",
    val scriptName: String = "",
    val findRegex: String = "",
    val replaceString: String = "",
    val disabled: Boolean = false,
    val runOnEdit: Boolean = true,
    val placement: List<Int> = emptyList(),  // 1=user, 2=ai
    val markdownOnly: Boolean = false,       // 仅影响显示（不改发送给模型的内容）
    val promptOnly: Boolean = false,         // 仅影响 prompt（发送），不用于显示
    val minDepth: Int? = null,               // 最小消息深度（包含），null/-1 = 无限制；深度从最后一条消息 0 向上递增
    val maxDepth: Int? = null,               // 最大消息深度（包含），null/-1 = 无限制
    val trimStrings: List<String> = emptyList(),  // 插入替换串前，从捕获组值中移除的字符串
    val substituteRegex: Int = 0,            // findRegex 宏替换：0 不替换 / 1 原样 / 2 正则转义后替换
)

data class WorldBookEntry(
    val id: Int,
    val keys: List<String>,
    val comment: String,       // 条目名称
    val content: String,       // 注入的文本
    val enabled: Boolean,
    val position: String,      // "after_char"、"before_char" 等（见 data.worldbook.WorldBookPos）
    val insertionOrder: Int,
    val constant: Boolean = false,  // 常驻条目：不做关键词扫描，始终注入
    val vectorized: Boolean = false, // 向量化激活状态（保真用；当前不做语义激活）
    val depth: Int = 4,             // position = at_depth 时的注入深度
    val role: Int = 0,              // position = at_depth 时的角色：0=System 1=User 2=Assistant
    val keySecondary: List<String> = emptyList(),  // 次级关键词（selective 为 true 时参与判定）
    val selectiveLogic: Int = 0,    // 同 ST：0=AND_ANY 1=NOT_ALL 2=NOT_ANY 3=AND_ALL
    val probability: Int = 100,     // 激活概率 %
    val caseSensitive: Boolean? = null,
)
