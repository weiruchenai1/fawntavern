package me.rerere.fawntavern.domain.chat

import me.rerere.fawntavern.data.chat.ChatSession

fun buildSearchSnippet(content: String, query: String): String {
    val flat = content.replace('\n', ' ')
    val index = flat.indexOf(query, ignoreCase = true)
    if (index < 0) return flat.take(50)
    val start = (index - 15).coerceAtLeast(0)
    val end = (index + query.length + 35).coerceAtMost(flat.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < flat.length) "…" else ""
    return "$prefix${flat.substring(start, end)}$suffix"
}

fun buildTitleHistory(
    session: ChatSession,
    userName: String,
    charName: String,
): String? {
    val userMessages = session.messages.filter { it.role == "user" }
    val assistantMessages = session.messages.filter { it.role == "assistant" }
    if (userMessages.isEmpty() || assistantMessages.isEmpty()) return null
    val pairCount = minOf(userMessages.size, assistantMessages.size, 2)
    return buildList {
        repeat(pairCount) { index ->
            add("$userName: ${userMessages[index].content.take(200)}")
            add("$charName: ${assistantMessages[index].content.take(200)}")
        }
    }.joinToString("\n")
}
