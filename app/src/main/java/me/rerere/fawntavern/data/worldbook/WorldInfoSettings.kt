package me.rerere.fawntavern.data.worldbook

/**
 * 全局世界书激活设置（对齐 SillyTavern #wiActivationSettings）。
 * 条目级 scanDepth/caseSensitive/matchWholeWords 覆盖此处的全局默认；null 时回落到这里。
 * 默认值取「保持本 App 既有行为 + 对齐用户截图」：不按预算裁剪、不带名称、子串匹配、递归开。
 */
data class WorldInfoSettings(
    val scanDepth: Int = 2,               // 扫描深度：从最新往前数几条消息做关键词扫描
    val budgetPercent: Int = 100,         // 上下文百分比：token 预算 = maxContext × 此百分比（100 = 实际不裁剪）
    val budgetCap: Int = 0,               // Token 预算上限（0 = 不额外封顶）
    val minActivations: Int = 0,          // 最小激活数（不足则逐步加深扫描；0 = 关闭）
    val minActivationsDepthMax: Int = 0,  // 最小激活加深时的最大扫描深度（0 = 不限，到消息数为止）
    val maxRecursionSteps: Int = 0,       // 最大递归轮数（0 = 用安全上限）
    val includeNames: Boolean = false,    // 扫描文本带角色名前缀（"名字: 内容"）
    val recursive: Boolean = true,        // 递归扫描：已激活条目正文参与下一轮关键词匹配
    val caseSensitive: Boolean = false,   // 全局默认：区分大小写
    val matchWholeWords: Boolean = false, // 全局默认：匹配整个单词
    val useGroupScoring: Boolean = false, // 全局默认：包含组按关键词命中数评分取胜
    val overflowAlert: Boolean = false,   // 预算溢出时提示（当前仅记录，不弹窗）
)
