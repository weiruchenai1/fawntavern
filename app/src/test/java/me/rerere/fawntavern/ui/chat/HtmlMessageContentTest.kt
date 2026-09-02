package me.rerere.fawntavern.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

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
    fun extractFencedHtmlMessageWrapsCssAndJavaScriptSections() {
        val extracted = extractFencedHtmlMessage(
            "```html\n<div class=\"card\">content</div>\n```\n" +
                "```css\n.card { color: red; }\n```\n" +
                "```javascript\nwindow.cardReady = true;\n```",
        ).orEmpty()

        assertTrue(extracted.contains("<div class=\"card\">content</div>"))
        assertTrue(extracted.contains("<style>\n.card { color: red; }\n</style>"))
        assertTrue(extracted.contains("<script>\nwindow.cardReady = true;\n</script>"))
        assertFalse(extracted.contains("```"))
    }

    @Test
    fun extractFencedHtmlMessageAcceptsUntypedFrontendDocument() {
        assertEquals(
            "<!doctype html>\n<html><body><main>card</main></body></html>",
            extractFencedHtmlMessage(
                "```\n<!doctype html>\n<html><body><main>card</main></body></html>\n```",
            ),
        )
    }

    @Test
    fun extractFencedHtmlMessageAcceptsArbitraryLanguageAndTildeFence() {
        assertEquals(
            "<html><body>card</body></html>",
            extractFencedHtmlMessage("~~~~frontend\n<html><body>card</body></html>\n~~~~"),
        )
    }

    @Test
    fun extractFencedHtmlMessageLeavesUnrelatedCodeFenceAlone() {
        val extracted = extractFencedHtmlMessage(
            "```html\n<div>card</div>\n```\n```kotlin\nval value = 1\n```",
        ).orEmpty()

        assertTrue(extracted.contains("<div>card</div>"))
        assertTrue(extracted.contains("```kotlin\nval value = 1\n```"))
    }

    @Test
    fun extractFencedHtmlMessageAcceptsUnclosedFrontendFence() {
        assertEquals(
            "<html><body>unfinished but renderable</body></html>",
            extractFencedHtmlMessage("```html\n<html><body>unfinished but renderable</body></html>"),
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

    @Test
    fun sanitizeHtmlRemovesContentJavaScriptWhenDisabled() {
        val sanitized = sanitizeHtml(
            "<style>.card{color:red}</style><div onclick=\"run()\">card</div><script>run()</script>" +
                "<iframe src=\"https://example.com/card\" srcdoc=\"<button onclick='run()'>run</button>\"></iframe>",
            allowContentJavaScript = false,
        )

        assertTrue(sanitized.contains(".card"))
        assertTrue(sanitized.contains("card"))
        assertFalse(sanitized.contains("onclick"))
        assertFalse(sanitized.contains("<script"))
        assertFalse(sanitized.contains("https://example.com/card"))
    }

    @Test
    fun sanitizeHtmlKeepsInlineJavaScriptWhenEnabled() {
        val sanitized = sanitizeHtml(
            "<button onclick=\"run()\">run</button><script src=\"https://example.com/card.js\"></script><script>run()</script>",
            allowContentJavaScript = true,
        )

        assertTrue(sanitized.contains("onclick"))
        assertTrue(sanitized.contains("<script"))
        assertTrue(sanitized.contains("https://example.com/card.js"))
    }

    @Test
    fun sanitizeHtmlKeepsExternalHttpsImages() {
        val sanitized = sanitizeHtml(
            "<figure><img loading=\"lazy\" src=\"https://example.com/card.png\" srcset=\"https://example.com/card@2x.png 2x\"></figure>",
            allowContentJavaScript = false,
        )

        assertTrue(sanitized.contains("https://example.com/card.png"))
        assertTrue(sanitized.contains("https://example.com/card@2x.png"))
        assertTrue(sanitized.contains("loading=\"eager\""))
    }

    @Test
    fun frontendVariablesKeepJsonValuesAndPlainStrings() {
        val encoded = encodeFrontendVariables(
            mapOf("score" to "42", "enabled" to "true", "name" to "Alice", "nested" to "{\"level\":3}"),
        )
        val json = JSONObject(encoded)

        assertEquals(42, json.getInt("score"))
        assertTrue(json.getBoolean("enabled"))
        assertEquals("Alice", json.getString("name"))
        assertEquals(3, json.getJSONObject("nested").getInt("level"))
        assertEquals(
            mapOf("score" to "42", "enabled" to "true", "name" to "Alice", "nested" to "{\"level\":3}"),
            decodeFrontendVariables(encoded),
        )
    }

    @Test
    fun frontendRuntimeMacrosExpandAvatarAndMessageContext() {
        val context = JSONObject()
            .put("userAvatarPath", "data:image/png;base64,user")
            .put("charAvatarPath", "data:image/png;base64,char")
            .put("lastMessageId", 8)
            .toString()

        assertEquals(
            "data:image/png;base64,user|data:image/png;base64,char|8",
            expandFrontendRuntimeMacros(
                "{{userAvatarPath}}|{{charAvatarPath}}|{{lastMessageId}}",
                context,
            ),
        )
    }
}
