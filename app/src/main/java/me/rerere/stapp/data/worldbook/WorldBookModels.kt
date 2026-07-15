package me.rerere.stapp.data.worldbook

data class WorldBook(
    val name: String = "",
    val entries: Map<Int, WorldBookEntry> = emptyMap(),
)

/**
 * 世界书条目（对齐 SillyTavern world-info 语义的子集）。
 * selectiveLogic 取值同 ST：0=AND_ANY 1=NOT_ALL 2=NOT_ANY 3=AND_ALL。
 */
data class WorldBookEntry(
    val id: Int,
    val keys: List<String>,
    val comment: String,
    val content: String,
    val enabled: Boolean = true,
    val position: String = "after_char",  // before_char / after_char / at_depth（ST 数字 position 已归一化）
    val insertionOrder: Int = 100,
    val constant: Boolean = false,        // 常驻条目：不做关键词扫描，始终注入
    val depth: Int = 4,                   // position = at_depth 时的注入深度（0 = 紧贴最新消息）
    val keySecondary: List<String> = emptyList(),  // 次级关键词（配合 selectiveLogic）
    val selectiveLogic: Int = 0,
    val probability: Int = 100,           // 激活概率 %（每次生成掷骰）
    val scanDepth: Int? = null,           // 条目级扫描深度覆盖（null = 用全局默认）
    val caseSensitive: Boolean? = null,   // 条目级大小写敏感覆盖（null = 不敏感）
    val matchWholeWords: Boolean? = null, // 条目级全词匹配覆盖（null = 子串匹配）
)
