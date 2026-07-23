package me.rerere.fawntavern.data.api

/**
 * 发送给 API 的一条消息（协议无关）。Prompt 拼装（PromptBuilder）产出它，
 * 各 ProviderAdapter 只负责把它编码成自家协议的 JSON。
 */
data class ApiMessage(
    val role: String,                       // "system" | "user" | "assistant"
    val content: String,
    val images: List<ApiImage> = emptyList(),
)

/** 已编码好的图片附件（base64，不带 data: 前缀） */
data class ApiImage(
    val mimeType: String,
    val base64: String,
)

/** 采样参数（来自关联预设）；null 字段 = 不下发，使用服务端默认值 */
data class GenParams(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val seed: Int? = null,
)

/** 合并相邻同角色消息（Claude/Gemini 要求 user/assistant 交替出现） */
internal fun mergeConsecutive(messages: List<ApiMessage>): List<ApiMessage> {
    val out = mutableListOf<ApiMessage>()
    for (m in messages) {
        val last = out.lastOrNull()
        if (last != null && last.role == m.role) {
            out[out.lastIndex] = last.copy(
                content = listOf(last.content, m.content).filter { it.isNotBlank() }.joinToString("\n\n"),
                images = last.images + m.images,
            )
        } else {
            out.add(m)
        }
    }
    return out
}

/**
 * 拆出开头连续的 system 消息（拼成单个系统提示串）与其余消息。
 * Claude 的 system 是顶层参数、Gemini 是 systemInstruction，均不允许出现在消息数组里；
 * 夹在对话中间的 system（如深度注入）由调用方降级为 user。
 */
internal fun splitLeadingSystem(messages: List<ApiMessage>): Pair<String, List<ApiMessage>> {
    val leading = messages.takeWhile { it.role == "system" }
    val system = leading.map { it.content }.filter { it.isNotBlank() }.joinToString("\n\n")
    return system to messages.drop(leading.size)
}
