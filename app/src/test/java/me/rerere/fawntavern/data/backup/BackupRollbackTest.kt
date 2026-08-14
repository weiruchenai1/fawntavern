package me.rerere.fawntavern.data.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BackupRollbackTest {
    @Test
    fun rollbackContinuesAcrossStorageFailuresAndRetainsDiagnostics() = runBlocking {
        val importError = IllegalStateException("import failed")
        val fileError = IllegalStateException("file rollback failed")
        val executed = mutableListOf<String>()
        val logged = mutableListOf<String>()

        rollbackAfterFailure(
            cause = importError,
            steps = listOf(
                BackupRollbackStep("room") { executed += "room" },
                BackupRollbackStep("file") {
                    executed += "file"
                    throw fileError
                },
                BackupRollbackStep("preferences") { executed += "preferences" },
            ),
            onStepFailure = { description, _ -> logged += description },
        )

        assertEquals(listOf("room", "file", "preferences"), executed)
        assertEquals(listOf("file"), logged)
        assertEquals(1, importError.suppressed.size)
        assertSame(fileError, importError.suppressed.single())
    }
}
