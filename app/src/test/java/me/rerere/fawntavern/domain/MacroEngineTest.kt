package me.rerere.fawntavern.domain

import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.random.Random
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroEngineTest {
    private val context = MacroContext(
        charName = "Fawn",
        userName = "Alice",
        card = CharacterCard(
            name = "Fawn",
            description = "A tavern keeper",
            personality = "Patient",
            scenario = "At the tavern",
            firstMes = "Welcome, {{user}}",
            alternateGreetings = listOf("Good evening"),
            creatorNotes = "Notes",
            systemPrompt = "System",
            postHistoryInstructions = "Post",
            mesExample = "Example",
        ),
        persona = "A traveler",
        history = listOf(
            ChatMessage(role = "user", content = "Hello"),
            ChatMessage(role = "assistant", content = "Welcome", altIdx = 1),
        ),
        input = "Current input",
        model = "test-model",
        maxContextTokens = 8_000,
        maxResponseTokens = 1_000,
        summary = "Earlier events",
        outlets = mapOf("lore" to "Hidden lore"),
        enabledExtensions = setOf("builtin.summarize"),
        sessionId = "session-1",
        now = ZonedDateTime.of(2026, 8, 13, 14, 5, 0, 0, ZoneOffset.ofHours(8)),
        random = Random(7),
    )

    @Test
    fun resolvesNestedMacrosAndDoubleColonArguments() {
        assertEquals("ecilA", MacroEngine.render("{{reverse::{{USER}}}}", context))
        assertEquals("Hidden lore", MacroEngine.render("{{outlet::Lore}}", context))
    }

    @Test
    fun resolvesSpaceAndLegacySingleColonArgumentForms() {
        assertEquals("dlroW olleH", MacroEngine.render("{{reverse Hello World}}", context))
        val roll = MacroEngine.render("{{roll:2d6+3}}", context).toInt()
        assertTrue(roll in 5..15)
        assertEquals("Alice/Fawn", MacroEngine.render("<USER>/<BOT>", context))
    }

    @Test
    fun supportsScopedContentNestedScopesAndElse() {
        val source = """
            {{if description}}
              {{reverse}}Hello {{user}}{{/reverse}}
            {{else}}
              missing
            {{/if}}
        """.trimIndent()

        assertEquals("ecilA olleH", MacroEngine.render(source, context))
        assertEquals("fallback", MacroEngine.render("{{if 0}}yes{{else}}fallback{{/if}}", context))
        assertEquals("inline", MacroEngine.render("{{if::true::inline}}", context))
    }

    @Test
    fun trimsScopeByDefaultAndPreservesItWithHashFlag() {
        assertEquals("ba", MacroEngine.render("{{reverse}} ab {{/reverse}}", context))
        assertEquals(" ba ", MacroEngine.render("{{#reverse}} ab {{/reverse}}", context))
    }

    @Test
    fun removesCommentsAndUnescapesLiteralMacroBraces() {
        val source = "before{{// hidden}}-{{ // }}also hidden{{ /// }}-\\{\\{user\\}\\}"
        assertEquals("before--{{user}}", MacroEngine.render(source, context))
    }

    @Test
    fun exposesCharacterHistoryRuntimeAndUtilityMacros() {
        val source = "{{description}}|{{persona}}|{{lastUserMessage}}|{{lastCharMessage}}|" +
            "{{model}}|{{maxPrompt}}|{{summary}}|{{hasExtension::BUILTIN.SUMMARIZE}}|" +
            "{{charFirstMessage::1}}|{{newline::2}}x"

        assertEquals(
            "A tavern keeper|A traveler|Hello|Welcome|test-model|7000|Earlier events|true|Good evening|\n\nx",
            MacroEngine.render(source, context),
        )
    }

    @Test
    fun formatsDatesAndTimezoneOffsets() {
        assertEquals("2026-08-13 14:05", MacroEngine.render("{{isodate}} {{isotime}}", context))
        assertEquals("06:05", MacroEngine.render("{{time::UTC}}", context))
        assertEquals("2026/08/13", MacroEngine.render("{{datetimeformat::YYYY/MM/DD}}", context))
    }

    @Test
    fun pickIsStableForTheSameSessionAndSourcePosition() {
        val source = "{{pick::red::green::blue}}"
        val first = MacroEngine.render(source, context)
        repeat(10) { assertEquals(first, MacroEngine.render(source, context)) }
        assertTrue(first in setOf("red", "green", "blue"))
    }

    @Test
    fun registryCarriesAutocompleteMetadata() {
        val ifMacro = MacroEngine.registry.definitions.single { it.name == "if" }
        assertTrue(ifMacro.supportsScope)
        assertEquals(listOf("condition", "content"), ifMacro.parameters.map { it.name })
    }

    @Test
    fun inlineScopedMacroDoesNotCaptureOuterClosingTag() {
        val source = "{{reverse}}A{{reverse::BC}}D{{/reverse}}"
        assertEquals("DBCA", MacroEngine.render(source, context))
    }

    @Test
    fun restrictedPolicyLeavesNonDisplayMacrosUntouched() {
        val result = MacroEngine.render(
            "{{user}}/{{time}}/{{random::a::b}}",
            context,
            MacroRenderPolicy.MESSAGE_DISPLAY,
        )
        assertEquals("Alice/{{time}}/{{random::a::b}}", result)
    }
}
