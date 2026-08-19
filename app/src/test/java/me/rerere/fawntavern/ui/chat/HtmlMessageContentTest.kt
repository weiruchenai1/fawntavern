package me.rerere.fawntavern.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlMessageContentTest {
    @Test
    fun stripStandaloneHtmlFenceRemovesOnlyOuterFenceLines() {
        assertEquals(
            "<div>`kept`</div>\n<style>main { color: red }</style>",
            stripStandaloneHtmlFence(
                "```html\n<div>`kept`</div>\n<style>main { color: red }</style>\n```",
            ),
        )
    }

    @Test
    fun stripStandaloneHtmlFenceLeavesOrdinaryBackticksAlone() {
        val source = "<div>Use ```html inline and `code` here</div>"
        assertEquals(source, stripStandaloneHtmlFence(source))
    }

    @Test
    fun extractStandaloneHtmlDocumentRequiresTheFenceToOwnTheMessage() {
        assertEquals(
            "<details><summary>Open</summary><iframe srcdoc=\"<p>inside</p>\"></iframe></details>",
            extractStandaloneHtmlDocument(
                "```html\n<details><summary>Open</summary><iframe srcdoc=\"<p>inside</p>\"></iframe></details>\n```",
            ),
        )
        assertEquals(null, extractStandaloneHtmlDocument("before\n```html\n<div>x</div>\n```"))
    }

    @Test
    fun stripStandaloneHtmlFenceLinesHandlesMultipleRenderedSections() {
        assertEquals(
            "<div>summary</div>\n<iframe srcdoc=\"<main>page</main>\"></iframe>",
            stripStandaloneHtmlFenceLines(
                "```\n<div>summary</div>\n```html\n<iframe srcdoc=\"<main>page</main>\"></iframe>\n```",
            ),
        )
    }

    @Test
    fun extractFencedHtmlMessageKeepsTheWholeRuntimeTogether() {
        assertEquals(
            "<style>.page{display:block}</style>\n<script>window.ready=true</script>\n<div class=\"page\">content</div>",
            extractFencedHtmlMessage(
                "```html\n<style>.page{display:block}</style>\n<script>window.ready=true</script>\n<div class=\"page\">content</div>\n```",
            ),
        )
    }

    @Test
    fun replaceViewportHeightUnitsUsesParentViewportVariable() {
        assertEquals(
            ".full{min-height:var(--TH-viewport-height);}.half{max-height:calc(var(--TH-viewport-height) * 0.5);}.fixed{height:calc(var(--TH-viewport-height) * 0.7);}",
            replaceViewportHeightUnits(
                ".full{min-height:100vh;}.half{max-height:50vh;}.fixed{height:70vh;}",
            ),
        )
    }
}
