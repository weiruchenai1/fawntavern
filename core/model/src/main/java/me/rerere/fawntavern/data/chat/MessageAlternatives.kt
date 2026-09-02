package me.rerere.fawntavern.data.chat

/** 同一消息位置的版本切换与删除规则。 */
object MessageAlternatives {
    /** 切换到相邻版本；没有实际切换时返回 null。 */
    fun switch(message: ChatMessage, direction: Int): ChatMessage? {
        if (message.alts.size < 2) return null
        val targetIndex = (message.altIdx + direction).coerceIn(0, message.alts.lastIndex)
        if (targetIndex == message.altIdx) return null

        val alternatives = message.alts.toMutableList()
        alternatives[message.altIdx] = alternatives[message.altIdx].copyFrom(message)
        return message.copyFrom(alternatives[targetIndex]).copy(
            alts = alternatives,
            altIdx = targetIndex,
        )
    }

    /** 删除当前版本；仅剩一个版本时返回 null，表示应删除整条消息。 */
    fun deleteCurrent(message: ChatMessage): ChatMessage? {
        if (message.alts.size <= 1) return null
        val alternatives = message.alts.toMutableList().also { it.removeAt(message.altIdx) }
        val targetIndex = message.altIdx.coerceAtMost(alternatives.lastIndex)
        val updated = message.copyFrom(alternatives[targetIndex])
        return if (alternatives.size == 1) {
            updated.copy(alts = emptyList(), altIdx = 0)
        } else {
            updated.copy(alts = alternatives, altIdx = targetIndex)
        }
    }

    private fun MsgAlt.copyFrom(message: ChatMessage): MsgAlt = copy(
        content = message.content,
        dataJson = message.dataJson,
        reasoning = message.reasoning,
        model = message.model,
        reasoningMs = message.reasoningMs,
        searches = message.searches,
        promptTokens = message.promptTokens,
        completionTokens = message.completionTokens,
        cachedTokens = message.cachedTokens,
        generationMs = message.generationMs,
        images = message.images,
        imageAspectRatio = message.imageAspectRatio,
        requestSnapshots = message.requestSnapshots,
    )

    private fun ChatMessage.copyFrom(alternative: MsgAlt): ChatMessage = copy(
        content = alternative.content,
        dataJson = alternative.dataJson,
        reasoning = alternative.reasoning,
        model = alternative.model,
        reasoningMs = alternative.reasoningMs,
        searches = alternative.searches,
        promptTokens = alternative.promptTokens,
        completionTokens = alternative.completionTokens,
        cachedTokens = alternative.cachedTokens,
        generationMs = alternative.generationMs,
        images = alternative.images,
        imageAspectRatio = alternative.imageAspectRatio,
        requestSnapshots = alternative.requestSnapshots,
    )
}
