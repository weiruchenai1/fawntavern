package me.rerere.fawntavern.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JsonFileDirTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        JsonFileDir.dir(context, "json-file-test").deleteRecursively()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalName() {
        JsonFileDir.file(context, "json-file-test", "../outside")
    }

    @Test
    fun sanitizesProviderNameAndAvoidsOverwrite() {
        val first = JsonFileDir.uniqueName(context, "json-file-test", "../book", "book")
        assertFalse(first.contains('/'))
        JsonFileDir.atomicWriteText(JsonFileDir.file(context, "json-file-test", first), "first")

        val second = JsonFileDir.uniqueName(context, "json-file-test", "../book", "book")

        assertEquals("_book (2)", second)
        assertEquals("first", JsonFileDir.file(context, "json-file-test", first).readText())
    }

    @Test
    fun atomicallyReplacesContentAndRemovesTemporaryFile() {
        val target = JsonFileDir.file(context, "json-file-test", "book")
        target.writeText("old")

        JsonFileDir.atomicWriteText(target, "new")

        assertEquals("new", target.readText())
        assertTrue(target.parentFile?.listFiles()?.none { it.extension == "tmp" } == true)
        assertFalse(File(context.filesDir, "outside.json").exists())
    }
}
