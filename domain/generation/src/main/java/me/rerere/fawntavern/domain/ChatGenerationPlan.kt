package me.rerere.fawntavern.domain

import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.domain.ConversationOps

enum class ChatGenerationMode { SEND, REGENERATE }

data class ChatGenerationPlan(
    val message: ChatMessage,
    val buildHistory: List<ChatMessage>,
    val promptHistory: List<ChatMessage>,
    val updateTimedWorldInfo: Boolean,
)

object ChatGenerationPlanner {
    fun create(
        session: ChatSession,
        modelId: String,
        mode: ChatGenerationMode,
        targetTimestamp: Long?,
    ): ChatGenerationPlan? = when (mode) {
        ChatGenerationMode.SEND -> {
            val message = ChatMessage(
                role = "assistant",
                model = modelId,
                ts = ConversationOps.nextTs(session),
            )
            ChatGenerationPlan(
                message = message,
                buildHistory = session.messages,
                promptHistory = session.messages,
                updateTimedWorldInfo = true,
            )
        }

        ChatGenerationMode.REGENERATE -> {
            val index = session.messages.indexOfFirst { it.ts == targetTimestamp }
            if (
                index < 0 ||
                session.messages[index].role != "assistant" ||
                session.messages.take(index).none { it.role == "user" }
            ) {
                null
            } else {
                ChatGenerationPlan(
                    message = ConversationOps.startVariantOne(session.messages[index], modelId),
                    buildHistory = session.messages.subList(0, index + 1),
                    promptHistory = session.messages.subList(0, index),
                    updateTimedWorldInfo = false,
                )
            }
        }
    }
}
