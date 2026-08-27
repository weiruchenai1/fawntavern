package me.rerere.fawntavern.data.api

/** 合并相邻同角色消息；带工具调用或协议原始块的消息不可合并。 */
internal fun mergeConsecutive(messages: List<ApiMessage>): List<ApiMessage> {
    val out = mutableListOf<ApiMessage>()
    for (message in messages) {
        val last = out.lastOrNull()
        if (
            last != null && last.role == message.role &&
            last.toolCalls.isEmpty() && message.toolCalls.isEmpty() &&
            last.rawBlocks.isEmpty() && message.rawBlocks.isEmpty()
        ) {
            out[out.lastIndex] = last.copy(
                content = listOf(last.content, message.content)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n"),
                images = last.images + message.images,
            )
        } else {
            out.add(message)
        }
    }
    return out
}

/** Claude 与 Gemini 将开头连续的 system 消息放在消息数组之外。 */
internal fun splitLeadingSystem(messages: List<ApiMessage>): Pair<String, List<ApiMessage>> {
    val leading = messages.takeWhile { it.role == "system" }
    val system = leading.map { it.content }.filter { it.isNotBlank() }.joinToString("\n\n")
    return system to messages.drop(leading.size)
}
