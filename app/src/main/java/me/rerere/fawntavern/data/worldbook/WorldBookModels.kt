package me.rerere.fawntavern.data.worldbook

data class WorldBook(
    val name: String = "",
    val entries: Map<Int, WorldBookEntry> = emptyMap(),
)

/**
 * 世界书条目（对齐 SillyTavern world-info 语义的子集）。
 * selectiveLogic 取值同 ST：0=AND_ANY 1=NOT_ALL 2=NOT_ANY 3=AND_ALL。
 * position 取 [WorldBookPos] 的 8 个字符串之一；role 仅在 position=at_depth 时有意义。
 */
data class WorldBookEntry(
    val id: Int,
    val keys: List<String>,
    val comment: String,
    val content: String,
    val enabled: Boolean = true,
    val position: String = WorldBookPos.AFTER_CHAR,  // 见 WorldBookPos（ST 数字 position 已归一化为字符串）
    val insertionOrder: Int = 100,
    val constant: Boolean = false,        // 常驻条目：不做关键词扫描，始终注入
    val vectorized: Boolean = false,      // 向量化激活（语义检索；需 embedding 后端，当前仅保留状态不激活）
    val depth: Int = 4,                   // position = at_depth 时的注入深度（0 = 紧贴最新消息）
    val role: Int = 0,                    // position = at_depth 时的角色：0=System 1=User 2=Assistant
    val outletName: String = "",          // position = outlet 时的插槽名（{{outlet::name}} 取用）
    val keySecondary: List<String> = emptyList(),  // 次级关键词（配合 selectiveLogic）
    val selectiveLogic: Int = 0,
    val probability: Int = 100,           // 激活概率 %（每次生成掷骰）
    val scanDepth: Int? = null,           // 条目级扫描深度覆盖（null = 用全局默认）
    val caseSensitive: Boolean? = null,   // 条目级大小写敏感覆盖（null = 用全局默认）
    val matchWholeWords: Boolean? = null, // 条目级全词匹配覆盖（null = 用全局默认）
    // 递归激活
    val excludeRecursion: Boolean = false,   // 不被其它条目递归触发（只匹配真实消息文本）
    val preventRecursion: Boolean = false,   // 自己激活后不加入递归缓冲（不触发下一轮）
    val delayUntilRecursion: Boolean = false,// 仅在递归轮激活（首轮跳过）
    // 包含组（互斥组）
    val group: String = "",               // 组名（逗号分隔可多组）；同组内只留 1 条
    val groupOverride: Boolean = false,   // 组内优先（无视加权随机）
    val groupWeight: Int = 100,           // 组内加权随机权重
    val useGroupScoring: Boolean = false, // 组内按关键词命中数评分取胜
    // 定时效果（按会话消息数计）
    val sticky: Int = 0,                  // 激活后保持 N 条消息
    val cooldown: Int = 0,                // 激活后冷却 N 条消息不可再激活
    val delay: Int = 0,                   // 聊天不足 N 条消息前不激活
)

/**
 * 条目注入位置。字符串值为内部规范表示，与 SillyTavern world_info_position（0..7）一一对应，
 * 集中在此转换以避免各处硬编码：
 * before=0 after=1 ANTop=2 ANBottom=3 atDepth=4 EMTop=5 EMBottom=6 outlet=7。
 */
object WorldBookPos {
    const val BEFORE_CHAR = "before_char"  // 0 角色定义之前
    const val AFTER_CHAR = "after_char"    // 1 角色定义之后
    const val AN_TOP = "an_top"            // 2 作者注释之前
    const val AN_BOTTOM = "an_bottom"      // 3 作者注释之后
    const val AT_DEPTH = "at_depth"        // 4 @D 在深度
    const val EM_TOP = "em_top"            // 5 示例消息之前
    const val EM_BOTTOM = "em_bottom"      // 6 示例消息之后
    const val OUTLET = "outlet"            // 7 具名插槽

    /** ST 数字 position → 内部字符串（未知值回落 after_char） */
    fun fromStId(id: Int): String = when (id) {
        0 -> BEFORE_CHAR
        2 -> AN_TOP
        3 -> AN_BOTTOM
        4 -> AT_DEPTH
        5 -> EM_TOP
        6 -> EM_BOTTOM
        7 -> OUTLET
        else -> AFTER_CHAR  // 1 及未知
    }

    /** 内部字符串 → ST 数字 position（用于 ST 兼容落盘） */
    fun toStId(pos: String): Int = when (pos) {
        BEFORE_CHAR -> 0
        AN_TOP -> 2
        AN_BOTTOM -> 3
        AT_DEPTH -> 4
        EM_TOP -> 5
        EM_BOTTOM -> 6
        OUTLET -> 7
        else -> 1  // AFTER_CHAR 及未知
    }

    /** 归一化任意来源（数字 / 数字串 / 字符串枚举）到内部字符串 */
    fun normalize(raw: Any?): String = when (raw) {
        is Number -> fromStId(raw.toInt())
        is String -> raw.toIntOrNull()?.let { fromStId(it) }
            ?: when (raw) {
                BEFORE_CHAR, AFTER_CHAR, AN_TOP, AN_BOTTOM, AT_DEPTH, EM_TOP, EM_BOTTOM, OUTLET -> raw
                else -> AFTER_CHAR
            }
        else -> AFTER_CHAR
    }
}
