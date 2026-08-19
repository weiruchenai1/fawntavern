package me.rerere.fawntavern.domain

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.chat.ChatMessage

/** All dynamic data visible to macros during one render pass. */
internal data class MacroContext(
    val charName: String = "",
    val userName: String = "",
    val card: CharacterCard? = null,
    val persona: String = "",
    val history: List<ChatMessage> = emptyList(),
    val input: String = "",
    val model: String = "",
    val maxContextTokens: Int = 0,
    val maxResponseTokens: Int = 0,
    val summary: String = "",
    val outlets: Map<String, String> = emptyMap(),
    val enabledExtensions: Set<String> = emptySet(),
    val original: String = "",
    val sessionId: String = "",
    val pickSalt: Int = 0,
    val now: ZonedDateTime = ZonedDateTime.now(),
    val random: Random = Random.Default,
    val variables: MacroVariableState = MacroVariableState(),
)

/** Mutable state shared by every macro expansion in one generation transaction. */
internal class MacroVariableState(
    localVariables: Map<String, String> = emptyMap(),
    globalVariables: Map<String, String> = emptyMap(),
) {
    private val initialLocal = localVariables.toMap()
    private val initialGlobal = globalVariables.toMap()
    private val local = localVariables.toMutableMap()
    private val global = globalVariables.toMutableMap()

    fun localVariables(): Map<String, String> = local.toMap()
    fun globalVariables(): Map<String, String> = global.toMap()
    fun initialGlobalVariables(): Map<String, String> = initialGlobal
    fun localChanged(): Boolean = local != initialLocal
    fun globalChanged(): Boolean = global != initialGlobal

    internal fun contains(globalScope: Boolean, name: String): Boolean =
        if (globalScope) global.containsKey(name) else local.containsKey(name)

    internal fun get(globalScope: Boolean, name: String): String? =
        if (globalScope) global[name] else local[name]

    internal fun set(globalScope: Boolean, name: String, value: String) {
        if (globalScope) global[name] = value else local[name] = value
    }

    internal fun remove(globalScope: Boolean, name: String) {
        if (globalScope) global.remove(name) else local.remove(name)
    }
}

internal data class MacroParameter(
    val name: String,
    val optional: Boolean = false,
    val description: String = "",
)

internal data class MacroDefinition(
    val name: String,
    val description: String,
    val parameters: List<MacroParameter> = emptyList(),
    val aliases: Set<String> = emptySet(),
    val supportsScope: Boolean = false,
    val evaluate: MacroRuntime.(MacroInvocation) -> String,
)

internal data class MacroInvocation(
    val name: String,
    val args: List<String>,
    val sourceOffset: Int,
    val body: (() -> String)? = null,
    val elseBody: (() -> String)? = null,
)

internal class MacroRegistry(definitions: List<MacroDefinition>) {
    private val byName = buildMap {
        definitions.forEach { definition ->
            put(definition.name.lowercase(), definition)
            definition.aliases.forEach { put(it.lowercase(), definition) }
        }
    }

    val definitions: List<MacroDefinition> = definitions.sortedBy { it.name }

    operator fun get(name: String): MacroDefinition? = byName[name.lowercase()]
}

internal data class MacroRenderPolicy(
    val allowedMacros: Set<String>? = null,
    val preserveUnknown: Boolean = true,
    val allowVariableMutations: Boolean = false,
) {
    fun allows(definition: MacroDefinition): Boolean =
        allowedMacros == null || definition.name.lowercase() in allowedMacros

    fun allows(name: String): Boolean = allowedMacros == null || name.lowercase() in allowedMacros

    companion object {
        val ALL = MacroRenderPolicy()
        val COMMIT_VARIABLES = MacroRenderPolicy(allowVariableMutations = true)
        val MESSAGE_DISPLAY = MacroRenderPolicy(setOf("char", "user", "newline"))
    }
}

internal object MacroEngine {
    private const val MAX_DEPTH = 32
    private const val MAX_EXPANSIONS = 2_000
    /** 展开最多能在原文之上追加的字符数：卡的是宏自身的膨胀，正文再长也不该因此被判超限 */
    private const val MAX_GROWTH_CHARS = 1_000_000

    val registry = MacroRegistry(builtIns())

    fun render(
        text: String,
        context: MacroContext,
        policy: MacroRenderPolicy = MacroRenderPolicy.ALL,
    ): String {
        if (text.isEmpty() || ('{' !in text && '<' !in text && '\\' !in text)) return text
        // 触顶只可能是宏递归失控。显示侧由 ChatMessageContent 在 composition 内同步调用，
        // 异常会直接崩掉主线程，因此降级为原文透传而不是抛出。
        return try {
            Renderer(context, policy, text.length + MAX_GROWTH_CHARS.toLong()).render(text)
        } catch (_: MacroLimitException) {
            text
        }
    }

    private class MacroLimitException(message: String) : RuntimeException(message)

    private inline fun limit(value: Boolean, message: () -> String) {
        if (!value) throw MacroLimitException(message())
    }

    private class Renderer(
        private val context: MacroContext,
        private val policy: MacroRenderPolicy,
        private val maxOutputChars: Long,
    ) {
        private var expansions = 0

        fun render(text: String): String = renderText(text, 0)

        private fun renderText(text: String, depth: Int): String {
            limit(depth <= MAX_DEPTH) { "Macro nesting exceeds $MAX_DEPTH levels" }
            val tokens = lex(normalizeLegacy(text))
            return renderRange(tokens, 0, tokens.size, depth).limitOutput()
        }

        private fun renderRange(tokens: List<Token>, start: Int, end: Int, depth: Int): String {
            val out = StringBuilder()
            var index = start
            while (index < end) {
                when (val token = tokens[index]) {
                    is Token.Text -> out.append(token.value)
                    is Token.Tag -> {
                        val header = parseHeader(token.raw)
                        if (header == null) {
                            out.append(token.source)
                        } else if (header.kind == TagKind.INLINE_COMMENT) {
                            // Removed from output.
                        } else if (header.kind == TagKind.COMMENT_OPEN) {
                            val close = findCommentClose(tokens, index + 1, end)
                            if (close == null) out.append(token.source) else index = close
                        } else if (header.kind != TagKind.OPEN) {
                            out.append(token.source)
                        } else if (header.variable != null) {
                            val macroName = if (header.variable.global) "getglobalvar" else "getvar"
                            if (!policy.allows(macroName)) out.append(token.source)
                            else {
                                expansions++
                                limit(expansions <= MAX_EXPANSIONS) { "Macro expansion count exceeds $MAX_EXPANSIONS" }
                                out.append(renderVariable(header.variable, token.offset, depth))
                            }
                        } else {
                            val definition = registry[header.name]
                            if (definition == null || !policy.allows(definition)) {
                                out.append(token.source)
                            } else {
                                var closeIndex: Int? = null
                                var elseIndex: Int? = null
                                val opensScope = isScopeOpening(header, definition)
                                if (opensScope) {
                                    closeIndex = findMatchingClose(tokens, index + 1, end, definition.name)
                                    if (closeIndex != null && definition.name.equals("if", true)) {
                                        elseIndex = findTopLevelElse(tokens, index + 1, closeIndex)
                                    }
                                }
                                if (opensScope && closeIndex == null) {
                                    out.append(token.source)
                                    index++
                                    continue
                                }
                                val args = header.args.map { renderText(it, depth + 1) }
                                val bodyStart = index + 1
                                val bodyEnd = elseIndex ?: closeIndex
                                val invocation = MacroInvocation(
                                    name = definition.name,
                                    args = args,
                                    sourceOffset = token.offset,
                                    body = closeIndex?.let {
                                        { scoped(renderRange(tokens, bodyStart, bodyEnd!!, depth + 1), header.preserveWhitespace) }
                                    },
                                    elseBody = elseIndex?.let {
                                        { scoped(renderRange(tokens, it + 1, closeIndex!!, depth + 1), header.preserveWhitespace) }
                                    },
                                )
                                expansions++
                                limit(expansions <= MAX_EXPANSIONS) { "Macro expansion count exceeds $MAX_EXPANSIONS" }
                                out.append(MacroRuntime(context, token.offset, policy.allowVariableMutations)
                                    .definitionEvaluate(definition, invocation))
                                if (closeIndex != null) index = closeIndex
                            }
                        }
                    }
                }
                limit(out.length <= maxOutputChars) { "Macro output exceeds $maxOutputChars characters" }
                index++
            }
            return out.toString()
        }

        private fun renderVariable(expression: VariableExpression, sourceOffset: Int, depth: Int): String {
            val runtime = MacroRuntime(context, sourceOffset, policy.allowVariableMutations)
            return runtime.evaluateVariable(expression) { raw -> renderText(raw, depth + 1) }
        }

        private fun String.limitOutput(): String {
            limit(length <= maxOutputChars) { "Macro output exceeds $maxOutputChars characters" }
            return this
        }
    }

    private sealed interface Token {
        data class Text(val value: String) : Token
        data class Tag(val raw: String, val source: String, val offset: Int) : Token
    }

    private enum class TagKind { OPEN, CLOSE, ELSE, INLINE_COMMENT, COMMENT_OPEN, COMMENT_CLOSE }

    private data class Header(
        val kind: TagKind,
        val name: String = "",
        val args: List<String> = emptyList(),
        val preserveWhitespace: Boolean = false,
        val variable: VariableExpression? = null,
    )

    internal data class VariableExpression(
        val global: Boolean,
        val name: String,
        val operator: String,
        val operand: String = "",
    )

    private fun lex(text: String): List<Token> {
        val result = mutableListOf<Token>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                result += Token.Text(plain.toString())
                plain.setLength(0)
            }
        }

        var index = 0
        while (index < text.length) {
            when {
                text.startsWith("\\{\\{", index) -> {
                    plain.append("{{")
                    index += 4
                }
                text.startsWith("\\}\\}", index) -> {
                    plain.append("}}")
                    index += 4
                }
                text.startsWith("{{", index) -> {
                    val end = findTagEnd(text, index + 2)
                    if (end == null) {
                        plain.append(text[index++])
                    } else {
                        flush()
                        result += Token.Tag(
                            raw = text.substring(index + 2, end),
                            source = text.substring(index, end + 2),
                            offset = index,
                        )
                        index = end + 2
                    }
                }
                else -> plain.append(text[index++])
            }
        }
        flush()
        return result
    }

    private fun normalizeLegacy(text: String): String = text
        .replace("<USER>", "{{user}}")
        .replace("<BOT>", "{{char}}")
        .replace("<CHAR>", "{{char}}")
        .replace("<GROUP>", "{{char}}")
        .replace("<CHARIFNOTGROUP>", "{{char}}")

    private fun findTagEnd(text: String, from: Int): Int? {
        var nesting = 0
        var index = from
        while (index < text.length - 1) {
            when {
                text.startsWith("\\{\\{", index) || text.startsWith("\\}\\}", index) -> index += 4
                text.startsWith("{{", index) -> {
                    nesting++
                    index += 2
                }
                text.startsWith("}}", index) -> {
                    if (nesting == 0) return index
                    nesting--
                    index += 2
                }
                else -> index++
            }
        }
        return null
    }

    private fun parseHeader(raw: String): Header? {
        var value = raw.trim()
        if (value == "else") return Header(TagKind.ELSE)
        if (value == "///") return Header(TagKind.COMMENT_CLOSE)
        if (value.startsWith("//")) {
            return if (value == "//") Header(TagKind.COMMENT_OPEN) else Header(TagKind.INLINE_COMMENT)
        }

        var kind = TagKind.OPEN
        if (value.startsWith('/')) {
            kind = TagKind.CLOSE
            value = value.drop(1).trimStart()
        }
        var preserve = false
        while (value.startsWith('#')) {
            preserve = true
            value = value.drop(1).trimStart()
        }
        if (kind == TagKind.OPEN) parseVariableExpression(value)?.let {
            return Header(kind = kind, variable = it, preserveWhitespace = preserve)
        }
        val nameMatch = Regex("^[A-Za-z][A-Za-z0-9_-]*").find(value) ?: return null
        val name = nameMatch.value
        val rest = value.substring(nameMatch.range.last + 1)
        if (kind == TagKind.CLOSE) return Header(kind, name)
        val args = when {
            rest.isBlank() -> emptyList()
            rest.trimStart().startsWith("::") -> splitDoubleColon(rest.trimStart().drop(2))
            rest.trimStart().startsWith(':') -> listOf(rest.trimStart().drop(1).trim())
            else -> listOf(rest.trim())
        }
        return Header(kind, name, args, preserve)
    }

    private fun parseVariableExpression(value: String): VariableExpression? {
        val match = Regex("^([.$])([A-Za-z](?:[A-Za-z0-9_-]*[A-Za-z0-9])?)").find(value) ?: return null
        val rest = value.substring(match.range.last + 1).trim()
        if (rest.isEmpty()) return VariableExpression(match.groupValues[1] == "\$", match.groupValues[2], "")
        val operator = listOf("||=", "??=", "++", "--", "+=", "-=", "==", "!=", ">=", "<=", "||", "??", "=", ">", "<")
            .firstOrNull { rest.startsWith(it) } ?: return null
        val operand = rest.drop(operator.length).trim()
        if (operator !in setOf("++", "--") && operand.isEmpty()) return null
        if (operator in setOf("++", "--") && operand.isNotEmpty()) return null
        return VariableExpression(match.groupValues[1] == "\$", match.groupValues[2], operator, operand)
    }

    private fun splitDoubleColon(value: String): List<String> {
        val result = mutableListOf<String>()
        var nesting = 0
        var start = 0
        var index = 0
        while (index < value.length) {
            when {
                value.startsWith("{{", index) -> { nesting++; index += 2 }
                value.startsWith("}}", index) && nesting > 0 -> { nesting--; index += 2 }
                value.startsWith("::", index) && nesting == 0 -> {
                    result += value.substring(start, index).trim()
                    index += 2
                    start = index
                }
                else -> index++
            }
        }
        result += value.substring(start).trim()
        return result
    }

    private fun findCommentClose(tokens: List<Token>, from: Int, end: Int): Int? =
        (from until end).firstOrNull { index ->
            (tokens[index] as? Token.Tag)?.raw?.trim() == "///"
        }

    private fun findMatchingClose(tokens: List<Token>, from: Int, end: Int, name: String): Int? {
        var depth = 0
        for (index in from until end) {
            val token = tokens[index] as? Token.Tag ?: continue
            val header = parseHeader(token.raw) ?: continue
            if (!header.name.equals(name, true)) continue
            val definition = registry[header.name]
            if (header.kind == TagKind.OPEN && definition != null && isScopeOpening(header, definition)) depth++
            if (header.kind == TagKind.CLOSE) {
                if (depth == 0) return index
                depth--
            }
        }
        return null
    }

    private fun findTopLevelElse(tokens: List<Token>, from: Int, end: Int): Int? {
        val scopes = ArrayDeque<String>()
        for (index in from until end) {
            val token = tokens[index] as? Token.Tag ?: continue
            val header = parseHeader(token.raw) ?: continue
            when (header.kind) {
                TagKind.OPEN -> registry[header.name]?.let { definition ->
                    if (isScopeOpening(header, definition)) scopes.addLast(header.name.lowercase())
                }
                TagKind.CLOSE -> if (scopes.lastOrNull() == header.name.lowercase()) scopes.removeLast()
                TagKind.ELSE -> if (scopes.isEmpty()) return index
                else -> Unit
            }
        }
        return null
    }

    private fun scoped(value: String, preserveWhitespace: Boolean): String {
        if (preserveWhitespace) return value
        val rawLines = value.lines()
        val first = rawLines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return ""
        val last = rawLines.indexOfLast { it.isNotBlank() }
        val lines = rawLines.subList(first, last + 1)
        val firstIndent = lines.first().takeWhile { it == ' ' || it == '\t' }.length
        return lines.joinToString("\n") { line ->
            if (line.isBlank()) "" else line.drop(minOf(firstIndent, line.takeWhile { it == ' ' || it == '\t' }.length))
        }.trim()
    }

    private fun isScopeOpening(header: Header, definition: MacroDefinition): Boolean =
        definition.supportsScope && header.args.size < definition.parameters.size

    private fun builtIns(): List<MacroDefinition> = listOf(
        value("user", "Current user name") { userName },
        value("char", "Current character name") { charName },
        value("description", "Character description") { card?.description.orEmpty() },
        value("personality", "Character personality") { card?.personality.orEmpty() },
        value("scenario", "Character scenario") { card?.scenario.orEmpty() },
        value("persona", "User persona description") { persona },
        value("charPrompt", "Character system prompt") { card?.systemPrompt.orEmpty() },
        value("charInstruction", "Character post-history instruction") { card?.postHistoryInstructions.orEmpty() },
        value("charDepthPrompt", "Character depth prompt") { card?.depthPrompt?.prompt.orEmpty() },
        value("charCreatorNotes", "Character creator notes") { card?.creatorNotes.orEmpty() },
        value("mesExamples", "Character example dialogue") { card?.mesExample.orEmpty() },
        value("mesExamplesRaw", "Raw character example dialogue") { card?.mesExample.orEmpty() },
        MacroDefinition("charFirstMessage", "Character greeting", listOf(MacroParameter("index", true))) { call ->
            val greetings = listOfNotNull(card?.firstMes) + card?.alternateGreetings.orEmpty()
            greetings.getOrNull(call.args.firstOrNull()?.toIntOrNull() ?: 0).orEmpty()
        },
        value("original", "Original overridden prompt") { original },
        value("input", "Current chat input") { input },
        value("model", "Current model name") { model },
        value("maxContextTokens", "Maximum context tokens") { maxContextTokens.takeIf { it > 0 }?.toString().orEmpty() },
        value("maxResponseTokens", "Maximum response tokens") { maxResponseTokens.takeIf { it > 0 }?.toString().orEmpty() },
        value("maxPrompt", "Maximum prompt tokens") {
            if (maxContextTokens > 0) max(0, maxContextTokens - maxResponseTokens).toString() else ""
        },
        value("isMobile", "Whether the app runs on mobile") { "true" },
        value("summary", "Latest conversation summary") { summary },
        value("lastMessage", "Last chat message") { history.lastOrNull()?.content.orEmpty() },
        value("lastUserMessage", "Last user message") { history.lastOrNull { it.role == "user" }?.content.orEmpty() },
        value("lastCharMessage", "Last assistant message") { history.lastOrNull { it.role == "assistant" }?.content.orEmpty() },
        value("lastMessageId", "Zero-based last message index") { (history.size - 1).toString() },
        value("currentSwipeId", "One-based current response alternative") {
            history.lastOrNull()?.let { (it.altIdx + 1).toString() }.orEmpty()
        },
        value("lastSwipeId", "One-based final response alternative") {
            history.lastOrNull()?.let { if (it.alts.isEmpty()) "1" else it.alts.size.toString() }.orEmpty()
        },
        value("allChatRange", "Range covering the entire chat") { "0-${history.size - 1}" },
        MacroDefinition("outlet", "World-info outlet", listOf(MacroParameter("key"))) { call ->
            val key = call.args.firstOrNull().orEmpty()
            outlets.entries.firstOrNull { it.key.equals(key, true) }?.value.orEmpty()
        },
        MacroDefinition("hasExtension", "Whether an extension is enabled", listOf(MacroParameter("name"))) { call ->
            enabledExtensions.any { it.equals(call.args.firstOrNull(), true) }.toString()
        },
        variableGet("getvar", false),
        variableSet("setvar", false),
        variableAdd("addvar", false),
        variableIncrement("incvar", false, 1),
        variableIncrement("decvar", false, -1),
        variableHas("hasvar", false),
        variableDelete("deletevar", false),
        variableGet("getglobalvar", true),
        variableSet("setglobalvar", true),
        variableAdd("addglobalvar", true),
        variableIncrement("incglobalvar", true, 1),
        variableIncrement("decglobalvar", true, -1),
        variableHas("hasglobalvar", true),
        variableDelete("deleteglobalvar", true),
        MacroDefinition("newline", "Insert line breaks", listOf(MacroParameter("count", true))) { call ->
            "\n".repeat(call.args.firstOrNull()?.toIntOrNull()?.coerceIn(0, 1_000) ?: 1)
        },
        MacroDefinition("space", "Insert spaces", listOf(MacroParameter("count", true))) { call ->
            " ".repeat(call.args.firstOrNull()?.toIntOrNull()?.coerceIn(0, 10_000) ?: 1)
        },
        value("noop", "Produce an empty string") { "" },
        MacroDefinition("reverse", "Reverse text", listOf(MacroParameter("text", true)), supportsScope = true) { call ->
            (call.body?.invoke() ?: call.args.firstOrNull().orEmpty()).reversed()
        },
        MacroDefinition("trim", "Trim surrounding line breaks", listOf(MacroParameter("text", true)), supportsScope = true) { call ->
            (call.body?.invoke() ?: call.args.firstOrNull().orEmpty()).trim('\n', '\r')
        },
        MacroDefinition(
            "if",
            "Render one branch based on truthiness",
            listOf(MacroParameter("condition"), MacroParameter("content")),
            supportsScope = true,
        ) { call ->
            if (resolveCondition(call.args.firstOrNull().orEmpty())) {
                call.body?.invoke() ?: call.args.getOrNull(1).orEmpty()
            }
            else call.elseBody?.invoke().orEmpty()
        },
        MacroDefinition("time", "Current local time", listOf(MacroParameter("utcOffset", true))) { call ->
            val at = call.args.firstOrNull()?.let { parseUtcOffset(it) } ?: now
            at.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
        },
        value("date", "Current local date") { now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())) },
        value("weekday", "Current weekday") { now.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())) },
        value("isotime", "Current time in HH:mm format") { now.format(DateTimeFormatter.ofPattern("HH:mm")) },
        value("isodate", "Current date in ISO format") { now.format(DateTimeFormatter.ISO_LOCAL_DATE) },
        MacroDefinition("datetimeformat", "Format the current date and time", listOf(MacroParameter("format"))) { call ->
            val pattern = call.args.firstOrNull().orEmpty().replace("YYYY", "yyyy").replace("DD", "dd")
            runCatching { now.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault())) }.getOrDefault("")
        },
        MacroDefinition("random", "Choose a random argument", listOf(MacroParameter("values"))) { call ->
            call.args.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] }.orEmpty()
        },
        MacroDefinition("pick", "Choose a stable argument", listOf(MacroParameter("values"))) { call ->
            if (call.args.isEmpty()) "" else call.args[stableIndex(call, call.args.size)]
        },
        MacroDefinition("roll", "Roll a dice expression", listOf(MacroParameter("expression"))) { call ->
            roll(call.args.firstOrNull().orEmpty())
        },
    )

    private fun value(
        name: String,
        description: String,
        get: MacroContext.() -> String,
    ) = MacroDefinition(name, description) { context.get() }

    private fun variableGet(name: String, global: Boolean) = MacroDefinition(
        name, "Get a ${if (global) "global" else "local"} variable", listOf(MacroParameter("name")),
    ) { call -> variable(global, call.args.firstOrNull().orEmpty()).orEmpty() }

    private fun variableSet(name: String, global: Boolean) = MacroDefinition(
        name, "Set a ${if (global) "global" else "local"} variable",
        listOf(MacroParameter("name"), MacroParameter("value")), supportsScope = true,
    ) { call ->
        if (mutationsAllowed) setVariable(global, call.args.firstOrNull().orEmpty(), call.body?.invoke() ?: call.args.getOrNull(1).orEmpty())
        ""
    }

    private fun variableAdd(name: String, global: Boolean) = MacroDefinition(
        name, "Add to a ${if (global) "global" else "local"} variable",
        listOf(MacroParameter("name"), MacroParameter("value")), supportsScope = true,
    ) { call ->
        if (mutationsAllowed) addVariable(global, call.args.firstOrNull().orEmpty(), call.body?.invoke() ?: call.args.getOrNull(1).orEmpty())
        ""
    }

    private fun variableIncrement(name: String, global: Boolean, delta: Int) = MacroDefinition(
        name, "Change a ${if (global) "global" else "local"} variable by $delta", listOf(MacroParameter("name")),
    ) { call -> incrementVariable(global, call.args.firstOrNull().orEmpty(), delta, mutationsAllowed) }

    private fun variableHas(name: String, global: Boolean) = MacroDefinition(
        name, "Check whether a ${if (global) "global" else "local"} variable exists", listOf(MacroParameter("name")),
    ) { call -> hasVariable(global, call.args.firstOrNull().orEmpty()).toString() }

    private fun variableDelete(name: String, global: Boolean) = MacroDefinition(
        name, "Delete a ${if (global) "global" else "local"} variable", listOf(MacroParameter("name")),
    ) { call ->
        if (mutationsAllowed) deleteVariable(global, call.args.firstOrNull().orEmpty())
        ""
    }
}

internal class MacroRuntime(
    val context: MacroContext,
    private val sourceOffset: Int,
    internal val mutationsAllowed: Boolean = false,
) {
    val charName get() = context.charName
    val userName get() = context.userName
    val card get() = context.card
    val persona get() = context.persona
    val history get() = context.history
    val input get() = context.input
    val model get() = context.model
    val maxContextTokens get() = context.maxContextTokens
    val maxResponseTokens get() = context.maxResponseTokens
    val summary get() = context.summary
    val outlets get() = context.outlets
    val enabledExtensions get() = context.enabledExtensions
    val original get() = context.original
    val now get() = context.now
    val random get() = context.random

    fun variable(global: Boolean, name: String): String? = context.variables.get(global, name)
    fun hasVariable(global: Boolean, name: String): Boolean = context.variables.contains(global, name)
    fun setVariable(global: Boolean, name: String, value: String) = context.variables.set(global, name, value)
    fun deleteVariable(global: Boolean, name: String) = context.variables.remove(global, name)

    fun addVariable(global: Boolean, name: String, operand: String): String {
        val current = variable(global, name).orEmpty()
        val left = current.toBigDecimalOrNull()
        val right = operand.toBigDecimalOrNull()
        val value = if (left != null && right != null) formatNumber(left + right) else current + operand
        setVariable(global, name, value)
        return value
    }

    fun incrementVariable(global: Boolean, name: String, delta: Int, mutate: Boolean): String {
        val current = variable(global, name)
        val number = current?.toBigDecimalOrNull() ?: if (current == null) BigDecimal.ZERO else return current
        if (!mutate) return current.orEmpty()
        val value = formatNumber(number + delta.toBigDecimal())
        setVariable(global, name, value)
        return value
    }

    internal fun evaluateVariable(expression: MacroEngine.VariableExpression, renderOperand: (String) -> String): String {
        val global = expression.global
        val name = expression.name
        val current = variable(global, name)
        fun operand() = renderOperand(expression.operand)
        return when (expression.operator) {
            "" -> current.orEmpty()
            "=" -> { if (mutationsAllowed) setVariable(global, name, operand()); "" }
            "++" -> incrementVariable(global, name, 1, mutationsAllowed)
            "--" -> incrementVariable(global, name, -1, mutationsAllowed)
            "+=" -> { if (mutationsAllowed) addVariable(global, name, operand()); "" }
            "-=" -> {
                if (mutationsAllowed) {
                    val left = current?.toBigDecimalOrNull()
                    val right = operand().toBigDecimalOrNull()
                    if (left != null && right != null) setVariable(global, name, formatNumber(left - right))
                }
                ""
            }
            "||" -> if (isTruthy(current.orEmpty())) current.orEmpty() else operand()
            "??" -> if (current != null) current else operand()
            "||=" -> if (isTruthy(current.orEmpty())) current.orEmpty() else operand().also {
                if (mutationsAllowed) setVariable(global, name, it)
            }
            "??=" -> if (current != null) current else operand().also {
                if (mutationsAllowed) setVariable(global, name, it)
            }
            "==" -> ((current ?: "") == operand()).toString()
            "!=" -> ((current ?: "") != operand()).toString()
            ">", ">=", "<", "<=" -> compareNumbers(current, operand(), expression.operator)
            else -> ""
        }
    }

    internal fun definitionEvaluate(definition: MacroDefinition, invocation: MacroInvocation): String =
        definition.evaluate(this, invocation)

    fun resolveCondition(raw: String): Boolean {
        var value = raw.trim()
        val inverted = value.startsWith('!')
        if (inverted) value = value.drop(1).trim()
        val variableRef = Regex("^([.$])([A-Za-z](?:[A-Za-z0-9_-]*[A-Za-z0-9])?)$").matchEntire(value)
        if (variableRef != null) {
            value = variable(variableRef.groupValues[1] == "\$", variableRef.groupValues[2]).orEmpty()
        } else {
            val named = MacroEngine.registry[value]
            if (named != null && named.parameters.isEmpty()) {
                value = named.evaluate(this, MacroInvocation(named.name, emptyList(), sourceOffset))
            }
        }
        val truthy = isTruthy(value)
        return if (inverted) !truthy else truthy
    }

    fun parseUtcOffset(raw: String): ZonedDateTime? {
        if (raw.trim().equals("UTC", true)) return context.now.withZoneSameInstant(java.time.ZoneOffset.UTC)
        val match = Regex("(?i)^UTC([+-])(\\d{1,2})(?::?(\\d{2}))?$").matchEntire(raw.trim()) ?: return null
        val hours = match.groupValues[2].toIntOrNull() ?: return null
        val minutes = match.groupValues[3].toIntOrNull() ?: 0
        val total = (hours * 60 + minutes) * if (match.groupValues[1] == "-") -1 else 1
        return runCatching { context.now.withZoneSameInstant(java.time.ZoneOffset.ofTotalSeconds(total * 60)) }.getOrNull()
    }

    fun stableIndex(call: MacroInvocation, size: Int): Int {
        var hash = 0xcbf29ce484222325UL
        val key = "${context.sessionId}|${context.pickSalt}|${call.sourceOffset}|${call.args.joinToString("\u001f")}" 
        key.encodeToByteArray().forEach { byte ->
            hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3UL
        }
        return (hash % size.toUInt().toULong()).toInt()
    }

    fun roll(expression: String): String {
        val match = Regex("(?i)^(\\d*)d(\\d+)([+-]\\d+)?$").matchEntire(expression.trim())
            ?: return expression.trim().toIntOrNull()?.takeIf { it > 0 }
                ?.let { (context.random.nextInt(it) + 1).toString() }.orEmpty()
        val count = match.groupValues[1].toIntOrNull() ?: 1
        val sides = match.groupValues[2].toIntOrNull() ?: return ""
        val modifier = match.groupValues[3].toIntOrNull() ?: 0
        if (count !in 1..100 || sides !in 1..1_000_000) return ""
        return ((1..count).sumOf { context.random.nextInt(sides) + 1 } + modifier).toString()
    }

    private fun compareNumbers(left: String?, right: String, operator: String): String {
        val l = left?.toBigDecimalOrNull() ?: return "false"
        val r = right.toBigDecimalOrNull() ?: return "false"
        val comparison = l.compareTo(r)
        return when (operator) {
            ">" -> comparison > 0
            ">=" -> comparison >= 0
            "<" -> comparison < 0
            else -> comparison <= 0
        }.toString()
    }

    private fun formatNumber(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private fun isTruthy(value: String): Boolean =
        value.trim().lowercase() !in setOf("", "false", "0", "off", "no")
}
