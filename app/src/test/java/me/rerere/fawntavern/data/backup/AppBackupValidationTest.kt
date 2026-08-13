package me.rerere.fawntavern.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppBackupValidationTest {
    @Test
    fun extractsSupportedEntry() {
        val staging = Files.createTempDirectory("backup-test-").toFile()
        try {
            AppBackup.extractValidated(zipOf("characters/fawn.json" to "{}"), staging)

            assertEquals("{}", staging.resolve("characters/fawn.json").readText())
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversal() {
        val staging = Files.createTempDirectory("backup-test-").toFile()
        try {
            AppBackup.extractValidated(zipOf("../outside.json" to "bad"), staging)
        } finally {
            assertFalse(staging.parentFile?.resolve("outside.json")?.exists() ?: false)
            staging.deleteRecursively()
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
