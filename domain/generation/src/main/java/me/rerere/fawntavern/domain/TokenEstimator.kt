package me.rerere.fawntavern.domain

/** Conservative token estimate used for context budgeting when provider usage is unavailable. */
object TokenEstimator {
    fun estimate(text: String): Int {
        var cjk = 0
        var other = 0
        for (character in text) {
            val code = character.code
            if (
                code in 0x2E80..0x9FFF ||
                code in 0xAC00..0xD7AF ||
                code in 0xF900..0xFAFF
            ) {
                cjk++
            } else {
                other++
            }
        }
        return cjk + (other + 3) / 4
    }
}
