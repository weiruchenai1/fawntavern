package me.rerere.fawntavern.domain

import me.rerere.fawntavern.data.chat.ChatSession

sealed interface ChatRegenerationPlan {
    data class Regenerate(val targetTimestamp: Long) : ChatRegenerationPlan
    data class TruncateAndSend(val afterTimestamp: Long) : ChatRegenerationPlan
}

/** Resolves UI regeneration actions without performing persistence or generation. */
object ChatRegenerationPlanner {
    fun forAssistant(session: ChatSession, timestamp: Long): ChatRegenerationPlan? {
        val index = session.messages.indexOfFirst { it.ts == timestamp }
        if (index < 0 || session.messages[index].role != "assistant") return null
        if (session.messages.take(index).none { it.role == "user" }) return null
        return ChatRegenerationPlan.Regenerate(timestamp)
    }

    fun afterUser(session: ChatSession, timestamp: Long): ChatRegenerationPlan? {
        val index = session.messages.indexOfFirst { it.ts == timestamp }
        if (index < 0 || session.messages[index].role != "user") return null
        val next = session.messages.getOrNull(index + 1)
        return if (next?.role == "assistant") {
            forAssistant(session, next.ts)
        } else {
            ChatRegenerationPlan.TruncateAndSend(timestamp)
        }
    }
}
