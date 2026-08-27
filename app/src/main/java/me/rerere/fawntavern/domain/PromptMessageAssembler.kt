package me.rerere.fawntavern.domain

import java.io.File
import java.util.Base64
import me.rerere.fawntavern.data.api.ApiImage
import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.data.chat.ChatMessage

/** Assembles compiled prompt pieces, message history, and attachments into provider messages. */
internal object PromptMessageAssembler {
    private const val FILE_TEXT_MAX_CHARS = 100_000

    fun assemble(
        built: PromptBuilder.Built,
        history: List<ChatMessage>,
        baseDir: File?,
        mutateLastUserMessage: Boolean = false,
    ): List<ApiMessage> {
        val filteredHistory = history.filter {
            it.content.isNotBlank() || it.images.isNotEmpty() || it.files.isNotEmpty()
        }
        val historySize = filteredHistory.size
        val mutableMessageIndex = if (mutateLastUserMessage) {
            filteredHistory.indexOfLast { it.role == "user" }
        } else {
            -1
        }
        val allHistoryMessages = filteredHistory.mapIndexed { index, message ->
            var content = RegexEngine.applyForPrompt(
                message.content,
                built.promptRegex,
                depth = historySize - 1 - index,
                role = message.role,
                userName = built.userName,
                charName = built.charName,
            )
            content = MacroEngine.render(
                content,
                built.macroContext.copy(
                    history = filteredHistory,
                    pickSalt = built.macroContext.pickSalt * 31 + index,
                ),
                if (index == mutableMessageIndex) {
                    MacroRenderPolicy.COMMIT_VARIABLES
                } else {
                    MacroRenderPolicy.ALL
                },
            )
            if (message.files.isNotEmpty() && baseDir != null) {
                val blocks = message.files.mapNotNull { file ->
                    readFileText(File(baseDir, file.path))?.let {
                        "<file name=\"${file.name}\">\n$it\n</file>"
                    }
                }
                if (blocks.isNotEmpty()) {
                    content = (blocks.joinToString("\n\n") + "\n\n" + content).trim()
                }
            }
            ApiMessage(
                role = message.role,
                content = content,
                images = if (baseDir == null) {
                    emptyList()
                } else {
                    message.images.mapNotNull { loadImage(baseDir, it) }
                },
            )
        }
        val historyMessages = trimToBudget(built, allHistoryMessages)
        val historyCount = historyMessages.size
        val spliced = historyMessages.toMutableList()
        built.depthInjections
            .groupBy { (historyCount - it.depth).coerceIn(0, historyCount) }
            .entries
            .sortedByDescending { it.key }
            .forEach { (index, pieces) ->
                spliced.addAll(index, pieces.map { ApiMessage(it.role, it.content) })
            }
        return built.preHistory.map { ApiMessage(it.role, it.content) } +
            spliced +
            built.postHistory.map { ApiMessage(it.role, it.content) }
    }

    private fun trimToBudget(
        built: PromptBuilder.Built,
        messages: List<ApiMessage>,
    ): List<ApiMessage> {
        if (built.maxContext <= 0 || messages.isEmpty()) return messages
        val budget = (built.maxContext - built.maxTokens).coerceAtLeast(512)
        val fixed = (built.preHistory + built.postHistory).sumOf {
            TokenEstimator.estimate(it.content) + 4
        } + built.depthInjections.sumOf { TokenEstimator.estimate(it.content) + 4 }
        var used = fixed
        val kept = ArrayDeque<ApiMessage>()
        for (message in messages.asReversed()) {
            val cost = TokenEstimator.estimate(message.content) + message.images.size * 400 + 4
            if (kept.isNotEmpty() && used + cost > budget) break
            used += cost
            kept.addFirst(message)
        }
        return kept
    }

    private fun loadImage(baseDir: File, relativePath: String): ApiImage? = try {
        val file = File(baseDir, relativePath)
        if (!file.exists()) null else ApiImage(
            mimeType = when (file.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            },
            base64 = Base64.getEncoder().encodeToString(file.readBytes()),
        )
    } catch (_: Exception) {
        null
    }

    private fun readFileText(file: File): String? = try {
        if (!file.exists()) null else {
            val bytes = file.readBytes()
            if (bytes.take(8000).contains(0.toByte())) null else {
                val text = String(bytes, Charsets.UTF_8)
                if (text.length > FILE_TEXT_MAX_CHARS) {
                    text.take(FILE_TEXT_MAX_CHARS) + "\n…(truncated)"
                } else {
                    text
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}
