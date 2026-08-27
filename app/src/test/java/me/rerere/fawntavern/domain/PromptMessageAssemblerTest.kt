package me.rerere.fawntavern.domain

import me.rerere.fawntavern.data.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptMessageAssemblerTest {
    @Test
    fun assemblesPreHistoryDepthInjectionAndPostHistoryInOrder() {
        val built = PromptBuilder.Built(
            preHistory = listOf(PromptBuilder.Piece("system", "pre")),
            postHistory = listOf(PromptBuilder.Piece("system", "post")),
            depthInjections = listOf(
                PromptBuilder.DepthPiece("system", "depth", depth = 0),
            ),
        )

        val result = PromptMessageAssembler.assemble(
            built = built,
            history = listOf(
                ChatMessage(role = "user", content = "question"),
                ChatMessage(role = "assistant", content = "answer"),
            ),
            baseDir = null,
        )

        assertEquals(listOf("pre", "question", "answer", "depth", "post"), result.map { it.content })
    }

    @Test
    fun tokenBudgetDropsOldHistoryButKeepsNewestMessage() {
        val built = PromptBuilder.Built(maxContext = 600)
        val result = PromptMessageAssembler.assemble(
            built = built,
            history = listOf(
                ChatMessage(role = "user", content = "a".repeat(3_000)),
                ChatMessage(role = "assistant", content = "latest"),
            ),
            baseDir = null,
        )

        assertEquals(listOf("latest"), result.map { it.content })
    }
}
