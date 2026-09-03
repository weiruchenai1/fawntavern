package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContentPagingTest {
    @Test
    fun pagedWindowUsesLoadedMessagesInsteadOfInMemorySnapshot() {
        val paged = listOf(message(41), message(42))
        val inMemory = (1L..100L).map(::message)

        assertEquals(paged, pagedMessageWindow(paged, inMemory))
    }

    @Test
    fun pagedWindowDoesNotRenderLargeInMemoryFallbackBeforePagingLoads() {
        val largeHistory = (1L..61L).map(::message)

        assertTrue(pagedMessageWindow(emptyList(), largeHistory).isEmpty())
    }

    @Test
    fun pagedWindowKeepsSmallInMemoryFallbackForNewChats() {
        val newChat = listOf(message(1), message(2))

        assertEquals(newChat, pagedMessageWindow(emptyList(), newChat))
    }

    @Test
    fun messageIndexesPreserveSessionMessageIdsForPagedFrontendWindow() {
        val messages = listOf(message(10), message(20), message(30))

        assertEquals(mapOf(10L to 0, 20L to 1, 30L to 2), messageIndexesByTimestamp(messages))
    }

    @Test
    fun messageIndexesApplyTheLazyWindowOffset() {
        val messages = listOf(message(70), message(80))

        assertEquals(mapOf(70L to 60, 80L to 61), messageIndexesByTimestamp(messages, offset = 60))
    }

    @Test
    fun messageIndexesUsePersistedPositionsWhenHiddenRowsAreOutsideTheWindow() {
        val visibleWindow = listOf(message(30), message(40))

        assertEquals(
            mapOf(30L to 2, 40L to 3),
            messageIndexesForWindow(
                messages = visibleWindow,
                allTimestamps = listOf(10L, 20L, 30L, 40L),
                fallbackOffset = 1,
            ),
        )
    }

    private fun message(timestamp: Long) = ChatMessage(role = "assistant", ts = timestamp)
}
