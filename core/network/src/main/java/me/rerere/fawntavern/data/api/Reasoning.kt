package me.rerere.fawntavern.data.api

/**
 * 思考预算档位。
 *
 * [AUTO] 是默认值 —— 一个思考相关字段都不下发，完全保持"没有本功能"时的请求体。
 * 本 App 不维护模型能力表（模型只是用户填的字符串），对不支持思考的模型下发任何思考字段
 * 都会 400，所以 AUTO 是唯一无条件安全的档位；选其它档位 = 用户自己确认了该模型支持思考。
 *
 * [budgetTokens] 同时卡在 Claude 的下限（budget_tokens ≥ 1024）与 Gemini Flash 的上限
 * （thinkingBudget ≤ 24576）之内，各 Adapter 再按自家协议翻译成 token 数或 effort 字符串。
 */
enum class ReasoningLevel(val budgetTokens: Int, val effort: String) {
    OFF(0, "none"),
    AUTO(-1, "auto"),
    LOW(1_024, "low"),
    MEDIUM(4_096, "medium"),
    HIGH(12_288, "high"),
    XHIGH(24_576, "xhigh");

    /** 是否要求模型思考。OFF 是"显式关闭"（仍要下发关闭字段），与"不下发"的 AUTO 不同 */
    val isEnabled: Boolean get() = this != OFF && this != AUTO

    companion object {
        fun fromName(name: String?): ReasoningLevel = entries.firstOrNull { it.name == name } ?: AUTO
    }
}
