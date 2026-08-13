package me.rerere.fawntavern.domain

import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.data.worldbook.WorldBookEntry
import me.rerere.fawntavern.data.worldbook.WorldInfoSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    @Test
    fun minActivationsDeepensHistoryScan() {
        val book = WorldBook(
            entries = mapOf(
                1 to entry(1, keys = listOf("old-key"), content = "activated lore"),
            ),
        )
        val built = PromptBuilder.build(
            card = CharacterCard(name = "Fawn"),
            userName = "user",
            worldBooks = listOf(book),
            history = listOf(
                ChatMessage(role = "user", content = "old-key appears here"),
                ChatMessage(role = "assistant", content = "latest message"),
            ),
            wiSettings = WorldInfoSettings(
                scanDepth = 1,
                minActivations = 1,
                minActivationsDepthMax = 2,
            ),
        )

        assertTrue(built.preHistory.any { "activated lore" in it.content })
    }

    @Test
    fun tokenBudgetKeepsHighestPriorityEntryFirst() {
        val high = "A".repeat(40)
        val low = "B".repeat(40)
        val book = WorldBook(
            entries = mapOf(
                1 to entry(1, content = high, order = 200, constant = true),
                2 to entry(2, content = low, order = 100, constant = true),
            ),
        )
        val built = PromptBuilder.build(
            card = CharacterCard(name = "Fawn"),
            userName = "user",
            worldBooks = listOf(book),
            wiSettings = WorldInfoSettings(budgetCap = 15),
        )
        val prompt = built.preHistory.joinToString("\n") { it.content }

        assertTrue(high in prompt)
        assertTrue(low !in prompt)
    }

    private fun entry(
        id: Int,
        keys: List<String> = emptyList(),
        content: String,
        order: Int = 100,
        constant: Boolean = false,
    ) = WorldBookEntry(
        id = id,
        keys = keys,
        comment = "entry-$id",
        content = content,
        insertionOrder = order,
        constant = constant,
    )
}
