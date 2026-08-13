package me.rerere.fawntavern.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageContentParserTest {
    @Test
    fun prepareMarkdownNormalizesLineEndingsAndAddsHardBreaksOutsideFences() {
        val source = "one\r\ntwo\r\n```kotlin\r\nval x = 1\r\n```"

        assertEquals(
            "one  \ntwo  \n```kotlin\nval x = 1\n```",
            MessageContentParser.prepareMarkdown(source),
        )
    }

    @Test
    fun prepareMarkdownPreservesParagraphSeparators() {
        assertEquals(
            "first\n\nsecond",
            MessageContentParser.prepareMarkdown("first\r\n\r\nsecond"),
        )
    }

    @Test
    fun closeOpenCodeFenceAddsMatchingRenderOnlyFence() {
        assertEquals(
            "```json\n{\"ok\":true}\n```",
            MessageContentParser.closeOpenCodeFence("```json\n{\"ok\":true}"),
        )
        assertEquals("plain", MessageContentParser.closeOpenCodeFence("plain"))
    }

    @Test
    fun closeOpenCodeFenceLeavesClosedFenceUntouched() {
        val source = "~~~text\nhello\n~~~"
        assertEquals(source, MessageContentParser.closeOpenCodeFence(source))
    }
}
