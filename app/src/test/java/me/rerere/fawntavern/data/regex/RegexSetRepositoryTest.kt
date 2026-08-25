package me.rerere.fawntavern.data.regex

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RegexSetRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RegexSetRepository.setsDir(context).deleteRecursively()
    }

    @Test
    fun createAssignsUniqueIdsAndRenameKeepsIdentity() = runBlocking {
        val first = RegexSetRepository.create(context, "Regex")
        val second = RegexSetRepository.create(context, "Regex")

        assertTrue(first.id.isNotBlank())
        assertNotEquals(first.id, second.id)
        assertTrue(RegexSetRepository.rename(context, first.name, "Renamed"))
        assertEquals(first.id, RegexSetRepository.load(context, "Renamed").id)
    }
}
