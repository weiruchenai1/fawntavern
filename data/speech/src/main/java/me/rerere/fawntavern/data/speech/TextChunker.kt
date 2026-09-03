package me.rerere.fawntavern.data.speech

/**
 * 长文本分片：按段落 + 标点切分，再按 [maxChunkLength] 合并成可单次朗读的分片。
 */
class TextChunker(private val maxChunkLength: Int = 160) {
    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val punctuationRegex = "(?<=[。！？，、：;.!?:,\n])".toRegex()
        return text.split("\n\n").flatMap { paragraph ->
            if (paragraph.isBlank()) emptyList()
            else paragraph.split(punctuationRegex).asSequence()
                .map { it.trim() }.filter { it.isNotEmpty() }
                .fold(mutableListOf<StringBuilder>()) { acc, seg ->
                    if (acc.isEmpty() || acc.last().length + seg.length > maxChunkLength) {
                        acc.add(StringBuilder(seg))
                    } else {
                        acc.last().append(seg)
                    }
                    acc
                }.map { it.toString() }
        }
    }
}
