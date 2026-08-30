package me.rerere.fawntavern.data.api

/** 图片任务关闭上下文时只保留当前用户请求；开启时保留已由上层裁剪过的完整消息。 */
internal fun imageGenerationMessages(
    messages: List<ApiMessage>,
    includeContext: Boolean,
): List<ApiMessage> {
    if (includeContext) return messages
    return messages.lastOrNull { it.role == "user" && (it.content.isNotBlank() || it.images.isNotEmpty()) }
        ?.let(::listOf)
        ?: messages.lastOrNull { it.content.isNotBlank() }?.let(::listOf)
        .orEmpty()
}

/** 将消息式上下文降级为单 prompt 协议可接收的带角色文本。 */
internal fun imageGenerationPrompt(
    messages: List<ApiMessage>,
    includeContext: Boolean,
): String {
    val selected = imageGenerationMessages(messages, includeContext).filter { it.content.isNotBlank() }
    if (selected.size <= 1) return selected.singleOrNull()?.content.orEmpty()
    return selected.joinToString("\n\n") { message ->
        val role = when (message.role) {
            "system" -> "System context"
            "assistant" -> "Assistant"
            "user" -> "User"
            else -> message.role.replaceFirstChar { it.uppercase() }
        }
        "$role:\n${message.content}"
    }
}
