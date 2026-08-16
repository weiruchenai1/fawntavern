package me.rerere.fawntavern.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeLogTest {
    @Test
    fun formattedLogContainsOnlySafeEntryFields() {
        val formatted = SafeLog.format(
            listOf(
                SafeLogEntry(
                    timestampMillis = 0L,
                    level = SafeLogLevel.ERROR,
                    tag = "ChatViewModel",
                    event = "generation_failed",
                    errorType = "java.io.IOException",
                ),
            ),
        )

        assertTrue(formatted.contains("[ERROR] ChatViewModel / generation_failed / java.io.IOException"))
        assertFalse(formatted.contains("stack trace"))
    }
}
