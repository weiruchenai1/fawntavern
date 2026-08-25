package me.rerere.fawntavern.ui.settings

import android.content.Context
import me.rerere.fawntavern.data.api.ApiConfigStore
import me.rerere.fawntavern.data.backup.AppBackup
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.chat.ChatRepository
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.regex.RegexSetRepository
import me.rerere.fawntavern.data.worldbook.WorldBookRepository
import me.rerere.fawntavern.core.diagnostics.SafeLog
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

internal enum class DataCategoryKey { CHARACTERS, PRESETS, WORLDBOOKS, REGEXSETS, CHATS, SYSTEM_LOGS }

internal data class DataCategoryInfo(
    val key: DataCategoryKey,
    val itemCount: Int,
    val sizeBytes: Long,
    val fileCount: Int,
)

internal data class DataManagementSnapshot(
    val categories: List<DataCategoryInfo>,
    val apiCount: Int,
) {
    val totalItems: Int = categories.sumOf { it.itemCount }
    val totalSizeBytes: Long = categories.sumOf { it.sizeBytes }
}

internal class PendingBackup internal constructor(
    internal val file: File,
    val availableSections: Set<AppBackup.Section>,
)

internal interface DataManagementDataSource {
    suspend fun snapshot(): DataManagementSnapshot
    suspend fun clear(category: DataCategoryKey)
    suspend fun clearAll()
    fun resetApi(): Int
    suspend fun export(output: OutputStream, sections: Set<AppBackup.Section>)
    suspend fun cacheAndInspect(input: InputStream): PendingBackup
    suspend fun import(backup: PendingBackup, sections: Set<AppBackup.Section>): AppBackup.ImportResult
    fun discard(backup: PendingBackup)
}

internal class AndroidDataManagementDataSource(
    private val context: Context,
) : DataManagementDataSource {
    override suspend fun snapshot(): DataManagementSnapshot = DataManagementSnapshot(
        categories = DataCategoryKey.entries.map { key ->
            val dir = directory(key)
            val sizeBytes = if (key == DataCategoryKey.SYSTEM_LOGS) {
                SafeLog.storageSizeBytes()
            } else {
                dir?.let(::directorySize) ?: 0L
            }
            DataCategoryInfo(
                key = key,
                itemCount = itemCount(key),
                sizeBytes = sizeBytes,
                fileCount = if (key == DataCategoryKey.SYSTEM_LOGS) {
                    if (sizeBytes > 0) 1 else 0
                } else {
                    dir?.listFiles()?.size ?: 0
                },
            )
        },
        apiCount = ApiConfigStore.loadConfig(context).providers.size,
    )

    override suspend fun clear(category: DataCategoryKey) {
        when (category) {
            DataCategoryKey.CHARACTERS -> CharacterRepository.clear(context)
            DataCategoryKey.PRESETS -> PresetRepository.listNames(context)
                .filter { it != PresetRepository.defaultPresetName(context) }
                .forEach { CharacterRepository.deletePreset(context, it) }
            DataCategoryKey.WORLDBOOKS -> WorldBookRepository.listNames(context)
                .forEach { CharacterRepository.deleteWorldBook(context, it) }
            DataCategoryKey.REGEXSETS -> RegexSetRepository.listNames(context)
                .forEach { CharacterRepository.deleteRegexSet(context, it) }
            DataCategoryKey.CHATS -> ChatRepository.clear(context)
            DataCategoryKey.SYSTEM_LOGS -> SafeLog.clear()
        }
    }

    override suspend fun clearAll() {
        DataCategoryKey.entries.forEach { clear(it) }
    }

    override fun resetApi(): Int = ApiConfigStore.resetToDefaults(context).providers.size

    override suspend fun export(output: OutputStream, sections: Set<AppBackup.Section>) {
        AppBackup.export(context, output, sections)
    }

    override suspend fun cacheAndInspect(input: InputStream): PendingBackup {
        val cached = File.createTempFile("backup_selected_", ".zip", context.cacheDir)
        try {
            cached.outputStream().use { input.copyTo(it) }
            val available = cached.inputStream().use { AppBackup.inspect(context, it) }
            require(available.isNotEmpty()) { "No supported content found in backup" }
            return PendingBackup(cached, available)
        } catch (error: Exception) {
            cached.delete()
            throw error
        }
    }

    override suspend fun import(
        backup: PendingBackup,
        sections: Set<AppBackup.Section>,
    ): AppBackup.ImportResult = backup.file.inputStream().use {
        AppBackup.import(context, it, sections)
    }

    override fun discard(backup: PendingBackup) {
        backup.file.delete()
    }

    private suspend fun itemCount(key: DataCategoryKey): Int = when (key) {
        DataCategoryKey.CHARACTERS -> CharacterRepository.listNames(context).size
        DataCategoryKey.PRESETS -> PresetRepository.listNames(context).size
        DataCategoryKey.WORLDBOOKS -> WorldBookRepository.listNames(context).size
        DataCategoryKey.REGEXSETS -> RegexSetRepository.listNames(context).size
        DataCategoryKey.CHATS -> ChatRepository.count(context)
        DataCategoryKey.SYSTEM_LOGS -> SafeLog.snapshot().size
    }

    private fun directory(key: DataCategoryKey): File? = when (key) {
        DataCategoryKey.CHARACTERS -> CharacterRepository.charsDir(context)
        DataCategoryKey.PRESETS -> PresetRepository.presetsDir(context)
        DataCategoryKey.WORLDBOOKS -> WorldBookRepository.worldDir(context)
        DataCategoryKey.REGEXSETS -> RegexSetRepository.setsDir(context)
        DataCategoryKey.CHATS -> ChatRepository.storageDir(context)
        DataCategoryKey.SYSTEM_LOGS -> null
    }
}

internal class DataManagementController(
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

    suspend fun export(output: OutputStream, sections: Set<AppBackup.Section>) =
        dataSource.export(output, sections)

    suspend fun cacheAndInspect(input: InputStream): PendingBackup =
        dataSource.cacheAndInspect(input)

    suspend fun import(
        backup: PendingBackup,
        sections: Set<AppBackup.Section>,
    ): AppBackup.ImportResult = dataSource.import(backup, sections)

    fun discard(backup: PendingBackup?) {
        if (backup != null) dataSource.discard(backup)
    }
}

private fun directorySize(directory: File): Long =
    directory.listFiles()?.sumOf { file ->
        when {
            file.isFile -> file.length()
            file.isDirectory -> directorySize(file)
            else -> 0L
        }
    } ?: 0L

internal fun formatDataSize(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(locale, "%.1f MB", bytes.toDouble() / (1024 * 1024))
    else -> String.format(locale, "%.1f GB", bytes.toDouble() / (1024 * 1024 * 1024))
}
