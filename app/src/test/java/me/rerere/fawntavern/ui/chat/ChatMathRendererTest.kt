package me.rerere.fawntavern.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMathRendererTest {
    @Test
    fun splitsInlineAndDisplayMath() {
        val segments = splitMathSegments("before \$x\$ and \$\$y^2\$\$ after")

        assertEquals(listOf("before ", "x", " and ", "y^2", " after"), segments.map { it.text })
        assertEquals(listOf(false, true, false, true, false), segments.map { it.formula })
        assertEquals(listOf(false, false, false, true, false), segments.map { it.displayMode })
    }

    @Test
    fun leavesCodeAndTablesUntouched() {
        val source = "```text\n\$x\$\n```\n\n| value | z |\n| --- | --- |\n| \$y\$ | z |"

        assertTrue(splitMathSegments(source).isEmpty())
        assertEquals(source, prepareMath(source))
    }

    @Test
    fun preparesFormulaAsPrivateMarkdownImageLink() {
        val prepared = prepareMath("value \$x\$")

        assertTrue(prepared.startsWith("value ![latex](latex:i:"))
        assertTrue(prepared.endsWith(")"))
    }

    @Test
    fun supportsBracketDelimitersAndEscapedDollars() {
        val source = "inline \\(x + 1\\), display \\[y^2\\], price \\$9"
        val segments = splitMathSegments(source)

        assertEquals(listOf("inline ", "x + 1", ", display ", "y^2", ", price \\$9"), segments.map { it.text })
        assertEquals(listOf(false, true, false, true, false), segments.map { it.formula })
        assertEquals(listOf(false, false, false, true, false), segments.map { it.displayMode })
    }
}
