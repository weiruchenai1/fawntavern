package me.rerere.fawntavern.ui.chat

/** Pure text transformations shared by the chat renderer and regression tests. */
internal object MessageContentParser {
    /** Preserve paragraph breaks; otherwise promote single newlines to Markdown hard breaks. */
    fun prepareMarkdown(text: String): String {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        return if (normalized.contains("\n\n")) normalized else markdownWithHardBreaks(normalized)
    }

    private fun markdownWithHardBreaks(text: String): String {
        if (!text.contains('\n')) return text
        val lines = text.split("\n")
        val result = StringBuilder(text.length + lines.size * 2)
        var inFence = false
        var fenceMarker = ""
        lines.forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            if (!inFence && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                inFence = true
                fenceMarker = trimmed.take(3)
            } else if (inFence && trimmed.startsWith(fenceMarker)) {
                inFence = false
            }
            result.append(line)
            if (index != lines.lastIndex) {
                if (!inFence && line.isNotBlank()) result.append("  ")
                result.append('\n')
            }
        }
        return result.toString()
    }

    /** Add a render-only closing marker while a streamed GFM fence is incomplete. */
    fun closeOpenCodeFence(text: String): String {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        var openMarker: String? = null
        normalized.lineSequence().forEach { line ->
            val trimmed = line.dropWhile { it == ' ' || it == '\t' }
            if (line.length - trimmed.length > 3) return@forEach
            val marker = trimmed.takeWhile { it == '`' || it == '~' }
            if (marker.length < 3 || marker.any { it != marker.first() }) return@forEach
            val open = openMarker
            if (open == null) {
                openMarker = marker
            } else if (marker.first() == open.first() && marker.length >= open.length &&
                trimmed.drop(marker.length).isBlank()) {
                openMarker = null
            }
        }
        val marker = openMarker ?: return normalized
        return buildString(normalized.length + marker.length + 1) {
            append(normalized)
            if (!normalized.endsWith('\n')) append('\n')
            append(marker)
        }
    }
}
