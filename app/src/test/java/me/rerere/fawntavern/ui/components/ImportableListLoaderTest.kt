package me.rerere.fawntavern.ui.components

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportableListLoaderTest {
    @Test
    fun failedItemRemainsVisibleAndIsReported() = runBlocking {
        val result = loadImportableItems(
            listNames = { listOf("valid", "damaged") },
            loadItem = { name ->
                if (name == "damaged") error("invalid json")
                name.uppercase()
            },
        )

        assertEquals(listOf("valid", "damaged"), result.names)
        assertEquals(mapOf("valid" to "VALID"), result.items)
        assertTrue("damaged" in result.failures)
    }

    @Test
    fun listFailureIsPropagatedToTheScreenState() = runBlocking {
        val failure = IllegalStateException("storage unavailable")

        val thrown = runCatching {
            loadImportableItems<String>(
                listNames = { throw failure },
                loadItem = { it },
            )
        }.exceptionOrNull()

        assertSame(failure, thrown)
    }
}
