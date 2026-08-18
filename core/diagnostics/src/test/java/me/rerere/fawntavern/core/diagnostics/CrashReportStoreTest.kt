package me.rerere.fawntavern.core.diagnostics

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportStoreTest {
    @Test
    fun crashTimeUsesUtc() {
        assertEquals(
            "1970-01-01T00:00:00Z",
            CrashReportStore.deviceTimestamp(0L),
        )
    }

    @Test
    fun reportWithinLimitIsUnchanged() {
        val report = "normal report 中文"

        assertArrayEquals(report.toByteArray(Charsets.UTF_8), CrashReportStore.limitReportBytes(report))
    }

    @Test
    fun oversizedReportIsTruncatedAtValidUtf8Boundary() {
        val report = "中".repeat(CrashReportStore.MAX_REPORT_BYTES)

        val limited = CrashReportStore.limitReportBytes(report)
        val decoded = limited.toString(Charsets.UTF_8)

        assertTrue(limited.size <= CrashReportStore.MAX_REPORT_BYTES)
        assertTrue(decoded.endsWith("[Report truncated]\n"))
        assertFalse(decoded.contains('\uFFFD'))
    }

    @Test
    fun cleanupRemovesExpiredReportAndTemporaryFiles() {
        val directory = Files.createTempDirectory("crash-reports-").toFile()
        try {
            val report = directory.resolve("latest-crash.txt").apply {
                writeText("old")
                setLastModified(NOW - CrashReportStore.RETENTION_MILLIS - 1)
            }
            val temporary = directory.resolve("crash-orphan.tmp").apply { writeText("partial") }

            CrashReportStore.cleanupDirectory(directory, NOW)

            assertFalse(report.exists())
            assertFalse(temporary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleanupRemovesLegacyOversizedReport() {
        val directory = Files.createTempDirectory("crash-reports-").toFile()
        try {
            val report = directory.resolve("latest-crash.txt")
            Files.newOutputStream(report.toPath()).use { output ->
                output.write(ByteArray(CrashReportStore.MAX_REPORT_BYTES + 1))
            }

            CrashReportStore.cleanupDirectory(directory, NOW)

            assertFalse(report.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleanupKeepsCurrentReport() {
        val directory = Files.createTempDirectory("crash-reports-").toFile()
        try {
            val report = directory.resolve("latest-crash.txt").apply {
                writeText("current")
                setLastModified(NOW)
            }

            CrashReportStore.cleanupDirectory(directory, NOW)

            assertTrue(report.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val NOW = 2_000_000_000_000L
    }
}
