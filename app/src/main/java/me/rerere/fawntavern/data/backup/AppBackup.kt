package me.rerere.fawntavern.data.backup

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.fawntavern.data.api.ApiConfigStore
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.chat.AttachmentStore
import me.rerere.fawntavern.data.chat.ChatRepository
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.settings.GlobalVariableStore
import me.rerere.fawntavern.data.settings.SearchStore
import me.rerere.fawntavern.data.settings.TtsStore
import me.rerere.fawntavern.data.settings.UserProfileStore
import me.rerere.fawntavern.data.worldbook.WorldBookRepository

object AppBackup {
    private const val TAG = "AppBackup"
    private const val FORMAT_VERSION = 4
    private const val CHAT_ENTRY = "data/chats.json"
    private const val API_ENTRY = "data/api-config.json"
    private const val SEARCH_ENTRY = "data/search-config.json"
    private const val TTS_ENTRY = "data/tts-config.json"
    private const val AVATAR_ENTRY = "avatar/user_avatar"
    private const val MAX_ENTRIES = 10_000
    private const val MAX_ENTRY_BYTES = 50L * 1024 * 1024
    private const val MAX_CHAT_BYTES = 200L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 500L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    enum class Section {
        CHARACTERS,
        PRESETS,
        WORLDBOOKS,
        CHATS,
        API_CONFIG,
        SEARCH_CONFIG,
        TTS_CONFIG,
        AVATAR,
    }

    val defaultExportSections: Set<Section> = setOf(
        Section.CHARACTERS,
        Section.PRESETS,
        Section.WORLDBOOKS,
        Section.CHATS,
        Section.AVATAR,
    )

    @Serializable
    private data class ChatArchive(
        val formatVersion: Int = FORMAT_VERSION,
        val sessions: List<ChatSession> = emptyList(),
        val globalVariables: Map<String, String>? = null,
    )

    data class ImportResult(val files: Int, val sessions: Int)

    private data class RestoredFile(val target: File, val original: File?)

    suspend fun export(context: Context, output: OutputStream, sections: Set<Section>) =
        withContext(Dispatchers.IO) {
            require(sections.isNotEmpty()) { "No backup content selected" }
            ZipOutputStream(output).use { zip ->
                if (Section.CHARACTERS in sections) {
                    addDirectory(zip, "characters", CharacterRepository.charsDir(context))
                }
                if (Section.PRESETS in sections) {
                    addDirectory(zip, "presets", PresetRepository.presetsDir(context))
                }
                if (Section.WORLDBOOKS in sections) {
                    addDirectory(zip, "worldbooks", WorldBookRepository.worldDir(context))
                }
                if (Section.CHATS in sections) {
                    addDirectory(zip, "attachments", AttachmentStore.dir(context))
                    addTextEntry(
                        zip,
                        CHAT_ENTRY,
                        json.encodeToString(ChatArchive(
                            sessions = ChatRepository.list(context),
                            globalVariables = GlobalVariableStore.get(context),
                        )),
                    )
                }
                if (Section.API_CONFIG in sections) {
                    addTextEntry(zip, API_ENTRY, ApiConfigStore.exportPortable(context))
                }
                if (Section.SEARCH_CONFIG in sections) {
                    addTextEntry(zip, SEARCH_ENTRY, SearchStore.exportPortable(context))
                }
                if (Section.TTS_CONFIG in sections) {
                    addTextEntry(zip, TTS_ENTRY, TtsStore.exportPortable(context))
                }
                if (Section.AVATAR in sections) {
                    UserProfileStore.getAvatarPath(context)?.let(::File)?.takeIf { it.isFile }?.let { avatar ->
                        addFile(zip, AVATAR_ENTRY, avatar)
                    }
                }
            }
        }

    suspend fun inspect(context: Context, input: InputStream): Set<Section> =
        withContext(Dispatchers.IO) {
            withStaging(context, input) { availableSections(it) }
        }

    suspend fun import(
        context: Context,
        input: InputStream,
        sections: Set<Section>,
    ): ImportResult = withContext(Dispatchers.IO) {
        require(sections.isNotEmpty()) { "No backup content selected" }
        withStaging(context, input) { staging ->
            val available = availableSections(staging)
            require(sections.all { it in available }) { "Selected content is missing from the backup" }

            // Parse every selected structured file before changing live data.
            val chatArchive = if (Section.CHATS in sections) {
                json.decodeFromString<ChatArchive>(File(staging, CHAT_ENTRY).readText()).also {
                    require(it.formatVersion in 2..FORMAT_VERSION) {
                        "Unsupported backup version: ${it.formatVersion}"
                    }
                }
            } else null
            val apiConfig = if (Section.API_CONFIG in sections) {
                ApiConfigStore.parsePortable(File(staging, API_ENTRY).readText())
            } else null
            val searchConfig = if (Section.SEARCH_CONFIG in sections) {
                SearchStore.parsePortable(File(staging, SEARCH_ENTRY).readText())
            } else null
            val ttsConfig = if (Section.TTS_CONFIG in sections) {
                TtsStore.parsePortable(File(staging, TTS_ENTRY).readText())
            } else null

            val previousApi = apiConfig?.let { ApiConfigStore.loadConfig(context) }
            val previousSearch = searchConfig?.let {
                SearchStore.parsePortable(SearchStore.exportPortable(context))
            }
            val previousTts = ttsConfig?.let {
                TtsStore.parsePortable(TtsStore.exportPortable(context))
            }
            val previousGlobalVariables = chatArchive?.globalVariables?.let { GlobalVariableStore.get(context) }
            val previousSessions = if (Section.CHATS in sections) ChatRepository.list(context) else null
            val previousAvatarPath = if (Section.AVATAR in sections) {
                UserProfileStore.getAvatarPath(context)
            } else null

            val rollbackDir = File(staging, "_rollback").also { it.mkdirs() }
            val restored = mutableListOf<RestoredFile>()
            var restoredFiles = 0
            var avatarPathTouched = false
            var apiConfigTouched = false
            var searchConfigTouched = false
            var ttsConfigTouched = false
            var globalVariablesTouched = false
            var chatDatabaseTouched = false
            try {
                if (Section.CHARACTERS in sections) {
                    restoredFiles += restoreDirectory(
                        File(staging, "characters"), CharacterRepository.charsDir(context),
                        File(rollbackDir, "characters"), restored,
                    )
                }
                if (Section.PRESETS in sections) {
                    restoredFiles += restoreDirectory(
                        File(staging, "presets"), PresetRepository.presetsDir(context),
                        File(rollbackDir, "presets"), restored,
                    )
                }
                if (Section.WORLDBOOKS in sections) {
                    restoredFiles += restoreDirectory(
                        File(staging, "worldbooks"), WorldBookRepository.worldDir(context),
                        File(rollbackDir, "worldbooks"), restored,
                    )
                }
                if (Section.CHATS in sections) {
                    restoredFiles += restoreDirectory(
                        File(staging, "attachments"), AttachmentStore.dir(context),
                        File(rollbackDir, "attachments"), restored,
                    )
                }
                if (Section.AVATAR in sections) {
                    val avatarTarget = File(context.filesDir, "avatars/user_avatar")
                    restoreFile(File(staging, AVATAR_ENTRY), avatarTarget, File(rollbackDir, "avatar"), restored)
                    avatarPathTouched = true
                    UserProfileStore.setAvatarPath(context, avatarTarget.absolutePath)
                    restoredFiles++
                }

                apiConfig?.let {
                    apiConfigTouched = true
                    ApiConfigStore.saveConfig(context, it)
                }
                searchConfig?.let {
                    searchConfigTouched = true
                    SearchStore.importPortable(context, it)
                }
                ttsConfig?.let {
                    ttsConfigTouched = true
                    TtsStore.importPortable(context, it)
                }
                chatArchive?.globalVariables?.let {
                    globalVariablesTouched = true
                    GlobalVariableStore.set(context, it)
                }
                chatArchive?.let {
                    chatDatabaseTouched = true
                    ChatRepository.restore(context, it.sessions)
                }
            } catch (e: Exception) {
                val rollbackSteps = buildList {
                    previousSessions?.takeIf { chatDatabaseTouched }?.let { sessions ->
                        add(BackupRollbackStep("chat database") {
                            ChatRepository.replaceAll(context, sessions)
                        })
                    }
                    previousGlobalVariables?.takeIf { globalVariablesTouched }?.let { variables ->
                        add(BackupRollbackStep("global variables") {
                            GlobalVariableStore.set(context, variables)
                        })
                    }
                    previousTts?.takeIf { ttsConfigTouched }?.let { config ->
                        add(BackupRollbackStep("TTS configuration") {
                            TtsStore.importPortable(context, config)
                        })
                    }
                    previousSearch?.takeIf { searchConfigTouched }?.let { config ->
                        add(BackupRollbackStep("search configuration") {
                            SearchStore.importPortable(context, config)
                        })
                    }
                    previousApi?.takeIf { apiConfigTouched }?.let { config ->
                        add(BackupRollbackStep("API configuration") {
                            ApiConfigStore.saveConfig(context, config)
                        })
                    }
                    if (avatarPathTouched) {
                        add(BackupRollbackStep("avatar path") {
                            UserProfileStore.setAvatarPath(context, previousAvatarPath)
                        })
                    }
                    restored.asReversed().forEach { record ->
                        add(BackupRollbackStep("file ${record.target.absolutePath}") {
                            rollbackFile(record)
                        })
                    }
                }
                withContext(NonCancellable) {
                    rollbackAfterFailure(e, rollbackSteps) { description, rollbackError ->
                        Log.e(TAG, "Backup rollback failed: $description", rollbackError)
                    }
                }
                if (e.suppressed.isNotEmpty()) {
                    throw IOException(
                        "Backup import failed and ${e.suppressed.size} rollback step(s) also failed",
                        e,
                    ).also { incompleteRollback ->
                        e.suppressed.forEach(incompleteRollback::addSuppressed)
                    }
                }
                throw e
            }
            if (Section.CHATS in sections) {
                runCatching { ChatRepository.collectUnusedAttachments(context) }
            }
            ImportResult(restoredFiles, chatArchive?.sessions?.size ?: 0)
        }
    }

    private inline fun <T> withStaging(context: Context, input: InputStream, block: (File) -> T): T {
        val staging = File(context.cacheDir, "backup_import_${UUID.randomUUID()}").also { it.mkdirs() }
        return try {
            extractValidated(input, staging)
            block(staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun availableSections(staging: File): Set<Section> = buildSet {
        if (File(staging, "characters").listFiles()?.any { it.isFile } == true) add(Section.CHARACTERS)
        if (File(staging, "presets").listFiles()?.any { it.isFile } == true) add(Section.PRESETS)
        if (File(staging, "worldbooks").listFiles()?.any { it.isFile } == true) add(Section.WORLDBOOKS)
        if (File(staging, CHAT_ENTRY).isFile) add(Section.CHATS)
        if (File(staging, API_ENTRY).isFile) add(Section.API_CONFIG)
        if (File(staging, SEARCH_ENTRY).isFile) add(Section.SEARCH_CONFIG)
        if (File(staging, TTS_ENTRY).isFile) add(Section.TTS_CONFIG)
        if (File(staging, AVATAR_ENTRY).isFile) add(Section.AVATAR)
    }

    private fun addDirectory(zip: ZipOutputStream, prefix: String, dir: File) {
        dir.listFiles()?.filter { it.isFile }?.forEach { addFile(zip, "$prefix/${it.name}", it) }
    }

    private fun addFile(zip: ZipOutputStream, entryName: String, file: File) {
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun addTextEntry(zip: ZipOutputStream, entryName: String, content: String) {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    internal fun extractValidated(input: InputStream, staging: File) {
        var entries = 0
        var totalBytes = 0L
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries++
                require(entries <= MAX_ENTRIES) { "Backup contains too many files" }
                val target = validatedTarget(staging, entry.name, entry.isDirectory)
                if (!entry.isDirectory) {
                    target?.parentFile?.mkdirs()
                    val output = target?.outputStream()
                    try {
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var entryBytes = 0L
                        val entryLimit = if (entry.name == CHAT_ENTRY) MAX_CHAT_BYTES else MAX_ENTRY_BYTES
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            totalBytes += read
                            require(entryBytes <= entryLimit) { "Backup file is too large: ${entry.name}" }
                            require(totalBytes <= MAX_TOTAL_BYTES) { "Backup is too large" }
                            output?.write(buffer, 0, read)
                        }
                    } finally {
                        output?.close()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun validatedTarget(staging: File, entryName: String, directory: Boolean): File? {
        val roots = setOf("characters", "presets", "worldbooks", "attachments", "data", "avatar", "chats")
        if (directory) {
            require(entryName.trimEnd('/') in roots) { "Unsupported backup path: $entryName" }
            return null
        }
        if (entryName in setOf(CHAT_ENTRY, API_ENTRY, SEARCH_ENTRY, TTS_ENTRY, AVATAR_ENTRY)) {
            return File(staging, entryName)
        }
        val parts = entryName.split('/')
        require(parts.size == 2 && parts[1].isNotBlank()) { "Unsupported backup path: $entryName" }
        // Legacy backups copied live Room files under chats/. They were never safe to restore.
        if (parts[0] == "chats") return null
        require(parts[0] in setOf("characters", "presets", "worldbooks", "attachments")) {
            "Unsupported backup path: $entryName"
        }
        val fileName = parts[1]
        require(fileName != "." && fileName != ".." && !fileName.contains('\\')) { "Invalid backup path" }
        return File(File(staging, parts[0]), fileName)
    }

    private fun restoreDirectory(
        source: File,
        target: File,
        rollback: File,
        restoreLog: MutableList<RestoredFile>,
    ): Int {
        if (!source.isDirectory) return 0
        var restoredCount = 0
        source.listFiles()?.filter { it.isFile }?.forEach { file ->
            restoreFile(file, File(target, file.name), File(rollback, file.name), restoreLog)
            restoredCount++
        }
        return restoredCount
    }

    private fun restoreFile(
        source: File,
        target: File,
        rollbackFile: File,
        restoreLog: MutableList<RestoredFile>,
    ) {
        target.parentFile?.mkdirs()
        rollbackFile.parentFile?.mkdirs()
        val original = if (target.exists()) target.copyTo(rollbackFile, overwrite = true) else null
        restoreLog += RestoredFile(target, original)
        source.copyTo(target, overwrite = true)
    }

    private fun rollbackFile(record: RestoredFile) {
        val original = record.original
        if (original != null) {
            original.copyTo(record.target, overwrite = true)
            return
        }
        if (record.target.exists() && !record.target.delete()) {
            throw IOException("Unable to remove restored file: ${record.target.absolutePath}")
        }
    }
}
