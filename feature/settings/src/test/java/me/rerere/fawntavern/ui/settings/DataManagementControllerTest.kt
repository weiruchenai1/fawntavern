package me.rerere.fawntavern.ui.settings

import kotlinx.coroutines.runBlocking
import me.rerere.fawntavern.data.backup.BackupImportResult
import me.rerere.fawntavern.data.backup.BackupSection
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

class DataManagementControllerTest {
    @Test
    fun clearReturnsFreshSnapshot() = runBlocking {
        val source = FakeDataSource()
        val controller = DataManagementController(source)

        val result = controller.clear(DataCategoryKey.CHATS)

        assertEquals(listOf(DataCategoryKey.CHATS), source.cleared)
        assertEquals(1, source.snapshotCalls)
        assertEquals(3, result.totalItems)
    }

    @Test
    fun clearAllUsesSingleDataSourceOperationAndRefreshes() = runBlocking {
        val source = FakeDataSource()
        val controller = DataManagementController(source)

        controller.clearAll()

        assertEquals(1, source.clearAllCalls)
        assertEquals(1, source.snapshotCalls)
    }

    @Test
    fun formatsStorageSizesDeterministically() {
        assertEquals("900 B", formatDataSize(900, Locale.US))
        assertEquals("2 KB", formatDataSize(2048, Locale.US))
        assertEquals("1.5 MB", formatDataSize(1572864, Locale.US))
    }

    private class FakeDataSource : DataManagementDataSource {
        var snapshotCalls = 0
        var clearAllCalls = 0
        val cleared = mutableListOf<DataCategoryKey>()

        override suspend fun snapshot(): DataManagementSnapshot {
            snapshotCalls++
            return DataManagementSnapshot(
                listOf(DataCategoryInfo(DataCategoryKey.CHATS, 3, 1024, 1)),
                apiCount = 2,
            )
        }

        override suspend fun clear(category: DataCategoryKey) {
            cleared += category
        }

        override suspend fun clearAll() {
            clearAllCalls++
        }

        override fun resetApi(): Int = 1
        override suspend fun export(output: OutputStream, sections: Set<BackupSection>) = Unit
        override suspend fun cacheAndInspect(input: InputStream): PendingBackup = error("unused")
        override suspend fun import(
            backup: PendingBackup,
            sections: Set<BackupSection>,
        ): BackupImportResult = error("unused")
        override fun discard(backup: PendingBackup) = Unit
    }
}
