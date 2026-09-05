package me.rerere.fawntavern.core.version

data class SemanticVersion(
    private val numbers: List<Int>,
    private val preRelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        for (index in 0 until maxOf(numbers.size, other.numbers.size)) {
            val compared = numbers.getOrElse(index) { 0 }
                .compareTo(other.numbers.getOrElse(index) { 0 })
            if (compared != 0) return compared
        }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return when {
                preRelease.isEmpty() && other.preRelease.isEmpty() -> 0
                preRelease.isEmpty() -> 1
                else -> -1
            }
        }
        for (index in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val left = preRelease[index]
            val right = other.preRelease[index]
            val leftNumber = left.toIntOrNull()
            val rightNumber = right.toIntOrNull()
            val compared = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (compared != 0) return compared
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    companion object {
        fun parse(value: String): SemanticVersion? {
            val normalized = value.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('+')
            val core = normalized.substringBefore('-')
            val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
            if (numbers.isEmpty()) return null
            val preRelease = normalized.substringAfter('-', "")
                .takeIf(String::isNotBlank)
                ?.split('.')
                ?: emptyList()
            return SemanticVersion(numbers, preRelease)
        }
    }
}
