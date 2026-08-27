package me.rerere.fawntavern.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationActionGuardTest {
    @Test
    fun mutationIsBlockedDuringGeneration() {
        assertFalse(GenerationActionGuard.allowsMutation(generating = true))
    }

    @Test
    fun mutationIsAllowedWhenIdle() {
        assertTrue(GenerationActionGuard.allowsMutation(generating = false))
    }
}
