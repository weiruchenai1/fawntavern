package me.rerere.fawntavern.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCopySheetTest {
    @Test
    fun textWindowStopsAfterRequestedLines() {
        val text = "one\ntwo\nthree\nfour"

        assertEquals("one\ntwo\n".length, textWindowEnd(text, maxLines = 2, maxChars = 1_000))
    }

    @Test
    fun textWindowCapsAPathologicallyLongLineByCharacters() {
        val text = "x".repeat(100_000)

        assertEquals(16_000, textWindowEnd(text, maxLines = 100, maxChars = 16_000))
    }

    @Test
    fun textWindowKeepsTheWholeSmallValue() {
        val text = "one\ntwo"

        assertEquals(text.length, textWindowEnd(text, maxLines = 100, maxChars = 16_000))
    }

    @Test
    fun textWindowsOnlyExposeTheRequestedNumberOfBatches() {
        val text = "x".repeat(20_000)

        val ranges = textWindowRanges(text, batchCount = 2, linesPerBatch = 100, charsPerBatch = 4_000)

        assertEquals(2, ranges.size)
        assertEquals(0 until 4_000, ranges[0])
        assertEquals(4_000 until 8_000, ranges[1])
        assertTrue(ranges.last().last + 1 < text.length)
    }

    @Test
    fun transformedOutputIsSplitIntoLazyRenderBlocks() {
        val transformed = "x".repeat(9_000)

        val ranges = allTextWindowRanges(transformed, linesPerBatch = 100, charsPerBatch = 4_000)

        assertEquals(listOf(0 until 4_000, 4_000 until 8_000, 8_000 until 9_000), ranges)
    }

    @Test
    fun nextBatchLoadsOnlyNearTheLazyListEnd() {
        assertTrue(shouldLoadNextTextBatch(9, totalItems = 10, hasMoreText = true, loading = false))
        assertTrue(!shouldLoadNextTextBatch(2, totalItems = 10, hasMoreText = true, loading = false))
        assertTrue(!shouldLoadNextTextBatch(8, totalItems = 10, hasMoreText = true, loading = true))
    }
}
