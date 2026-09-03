package me.rerere.fawntavern.ui.settings

import me.rerere.fawntavern.data.backup.BackupImportResult
import me.rerere.fawntavern.data.backup.BackupSection
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

enum class DataCategoryKey { CHARACTERS, PRESETS, WORLDBOOKS, REGEXSETS, CHATS, SYSTEM_LOGS }

data class DataCategoryInfo(
    val key: DataCategoryKey,
    val itemCount: Int,
    val sizeBytes: Long,
    val fileCount: Int,
)

data class DataManagementSnapshot(
    val categories: List<DataCategoryInfo>,
    val apiCount: Int,
) {
    val totalItems: Int = categories.sumOf { it.itemCount }
    val totalSizeBytes: Long = categories.sumOf { it.sizeBytes }
}

class PendingBackup(
    val file: File,
    val availableSections: Set<BackupSection>,
)

interface DataManagementDataSource {
    suspend fun snapshot(): DataManagementSnapshot
    suspend fun clear(category: DataCategoryKey)
    suspend fun clearAll()
    fun resetApi(): Int
    suspend fun export(output: OutputStream, sections: Set<BackupSection>)
    suspend fun cacheAndInspect(input: InputStream): PendingBackup
    suspend fun import(backup: PendingBackup, sections: Set<BackupSection>): BackupImportResult
    fun discard(backup: PendingBackup)
}

class DataManagementController(
    private val dataSource: DataManagementDataSource,
) {
    suspend fun snapshot(): DataManagementSnapshot = dataSource.snapshot()

    suspend fun clear(category: DataCategoryKey): DataManagementSnapshot {
        dataSource.clear(category)
        return dataSource.snapshot()
    }

    suspend fun clearAll(): DataManagementSnapshot {
        dataSource.clearAll()
        return dataSource.snapshot()
    }

    fun resetApi(): Int = dataSource.resetApi()

    suspend fun export(output: OutputStream, sections: Set<BackupSection>) =
        dataSource.export(output, sections)

    suspend fun cacheAndInspect(input: InputStream): PendingBackup =
        dataSource.cacheAndInspect(input)

    suspend fun import(
        backup: PendingBackup,
        sections: Set<BackupSection>,
    ): BackupImportResult = dataSource.import(backup, sections)

    fun discard(backup: PendingBackup?) {
        if (backup != null) dataSource.discard(backup)
    }
}

fun formatDataSize(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(locale, "%.1f MB", bytes.toDouble() / (1024 * 1024))
    else -> String.format(locale, "%.1f GB", bytes.toDouble() / (1024 * 1024 * 1024))
}
