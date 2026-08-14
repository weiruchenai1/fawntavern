package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageWindowTest {
    @Test
    fun replacesLoadedRowsAndAppendsMissingRowsInTimestampOrder() {
        val base = listOf(message(1, "old"), message(2, "second"))
        val overlays = mapOf(
            3L to message(3, "third"),
            1L to message(1, "updated"),
            4L to message(4, "fourth"),
        )

        val merged = mergeMessageWindow(base, overlays, allowAppend = true)

        assertEquals(listOf(1L, 2L, 3L, 4L), merged.map { it.ts })
        assertEquals("updated", merged.first().content)
    }

    @Test
    fun doesNotAppendRowsWhenPagingWindowIsAwayFromBottom() {
        val base = listOf(message(1, "first"))
        val overlays = mapOf(1L to message(1, "updated"), 2L to message(2, "hidden"))

        val merged = mergeMessageWindow(base, overlays, allowAppend = false)

        assertEquals(listOf(1L), merged.map { it.ts })
        assertEquals("updated", merged.single().content)
    }

    @Test
    fun settlesPersistedOverlayExceptCurrentGenerationTarget() {
        val base = listOf(message(1, "same"), message(2, "same"), message(3, "old"))
        val overlays = mapOf(
            1L to message(1, "same"),
            2L to message(2, "same"),
            3L to message(3, "new"),
        )

        assertEquals(
            listOf(1L),
            settledOverlayTimestamps(base, overlays, generating = true, generationTargetTs = 2L),
        )
    }

    private fun message(ts: Long, content: String) =
        ChatMessage(role = "assistant", ts = ts, content = content)
}
