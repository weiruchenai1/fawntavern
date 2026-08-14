package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.chat.ChatMessage

internal fun mergeMessageWindow(
    base: List<ChatMessage>,
    overlays: Map<Long, ChatMessage>,
    allowAppend: Boolean,
): List<ChatMessage> {
    if (overlays.isEmpty()) return base
    val merged = base.map { overlays[it.ts] ?: it }
    if (!allowAppend) return merged
    val baseTimestamps = base.asSequence().map { it.ts }.toHashSet()
    return merged + overlays.values.filter { it.ts !in baseTimestamps }.sortedBy { it.ts }
}

internal fun settledOverlayTimestamps(
    base: List<ChatMessage>,
    overlays: Map<Long, ChatMessage>,
    generating: Boolean,
    generationTargetTs: Long?,
): List<Long> = overlays.values.filter { overlay ->
    !(generating && overlay.ts == generationTargetTs) && base.any { persisted ->
        persisted.ts == overlay.ts &&
            persisted.content == overlay.content &&
            persisted.reasoning == overlay.reasoning
    }
}.map { it.ts }
