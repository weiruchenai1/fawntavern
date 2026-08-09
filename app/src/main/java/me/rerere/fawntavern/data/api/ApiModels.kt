package me.rerere.fawntavern.data.api

/**
 * 发送给 API 的一条消息（协议无关）。Prompt 拼装（PromptBuilder）产出它，
 * 各 ProviderAdapter 只负责把它编码成自家协议的 JSON。
 */
data class ApiMessage(
    val role: String,                       // "system" | "user" | "assistant"
    val content: String,
    val images: List<ApiImage> = emptyList(),
    /**
     * assistant 发起的工具调用（含执行结果）。编码时 Adapter 把一条这样的消息展开为
     * "assistant 调用 + 工具结果"两个协议消息，多轮工具循环的历史得以用扁平数组表达。
     */
    val toolCalls: List<ApiToolCall> = emptyList(),
    /**
     * 协议私有的 assistant 原始内容块 JSON（流式解析时原样捕获）。Claude 的 thinking 块带
     * signature、Gemini 的 functionCall 带 thoughtSignature，回传时必须原样回显否则被拒；
     * 仅产生它的同协议 Adapter 使用，其它协议忽略并按 [toolCalls] 重建。
     */
    val rawBlocks: String = "",
)

/** 一次工具调用（模型发起）＋ App 执行后的结果文本 */
data class ApiToolCall(
    val id: String,          // OpenAI/Claude 的调用 id；Gemini 无 id，回传按 name 对应
    val name: String,
    val arguments: String,   // JSON 文本（模型给出的参数）
    val result: String = "", // App 执行结果（回传给模型的文本，通常为 JSON）
    val extra: String = "",  // 协议私有的调用级元数据（Gemini 的 thoughtSignature），回传时原样回显
)

/** 下发给模型的工具声明；parameters 为 JSON Schema 文本（各协议字段名不同，Adapter 自行包装） */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: String,
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
    /** 思考预算档位（来自 ThinkingStore，按模型记忆）；AUTO = 不下发任何思考字段 */
    val reasoning: ReasoningLevel = ReasoningLevel.AUTO,
)

/** 合并相邻同角色消息（Claude/Gemini 要求 user/assistant 交替出现）。
 *  带工具调用/原始块的消息不参与合并 —— 合并只保留文本，会丢掉调用与签名 */
internal fun mergeConsecutive(messages: List<ApiMessage>): List<ApiMessage> {
    val out = mutableListOf<ApiMessage>()
    for (m in messages) {
        val last = out.lastOrNull()
        if (last != null && last.role == m.role &&
            last.toolCalls.isEmpty() && m.toolCalls.isEmpty() &&
            last.rawBlocks.isEmpty() && m.rawBlocks.isEmpty()
        ) {
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
