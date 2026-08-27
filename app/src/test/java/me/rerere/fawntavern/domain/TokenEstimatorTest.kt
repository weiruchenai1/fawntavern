package me.rerere.fawntavern.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenEstimatorTest {
    @Test
    fun estimatesAsciiByFourCharactersPerToken() {
        assertEquals(1, TokenEstimator.estimate("abcd"))
        assertEquals(2, TokenEstimator.estimate("abcde"))
    }

    @Test
    fun estimatesCjkCharactersIndividually() {
        assertEquals(2, TokenEstimator.estimate("你好"))
    }
}
