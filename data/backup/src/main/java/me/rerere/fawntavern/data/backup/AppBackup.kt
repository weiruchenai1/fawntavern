package me.rerere.fawntavern.data.backup

import android.content.Context
import me.rerere.fawntavern.core.diagnostics.SafeLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
import me.rerere.fawntavern.data.regex.RegexSetRepository
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
    private const val RESTORE_TXN_DIR = ".backup-restore"
    private const val RESTORE_JOURNAL = "journal.json"
    private const val RESTORE_PREPARED = "PREPARED"
    private const val RESTORE_COMMITTED = "COMMITTED"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class ChatArchive(
        val formatVersion: Int = FORMAT_VERSION,
        val sessions: List<ChatSession> = emptyList(),
        val globalVariables: Map<String, String>? = null,
    )

    @Serializable
    private data class JournalFile(
        val area: String,
        val name: String,
        val existed: Boolean,
    )

    @Serializable
    private data class RestoreJournal(
        val files: List<JournalFile>,
        val previousApi: String? = null,
        val previousSearch: String? = null,
        val previousTts: String? = null,
        val previousChats: ChatArchive? = null,
        val restoreGlobalVariables: Boolean = false,
        val restoreAvatarPath: Boolean = false,
        val previousAvatarPath: String? = null,
    )

    private data class RestoredFile(val target: File, val original: File?)

    suspend fun export(context: Context, output: OutputStream, sections: Set<BackupSection>) =
        withContext(Dispatchers.IO) {
            require(sections.isNotEmpty()) { "No backup content selected" }
            ZipOutputStream(output).use { zip ->
                if (BackupSection.CHARACTERS in sections) {
                    addDirectory(zip, "characters", CharacterRepository.charsDir(context))
                }
                if (BackupSection.PRESETS in sections) {
                    PresetRepository.ensureAllIds(context)
                    addDirectory(zip, "presets", PresetRepository.presetsDir(context))
                }
                if (BackupSection.WORLDBOOKS in sections) {
                    WorldBookRepository.ensureAllIds(context)
                    addDirectory(zip, "worldbooks", WorldBookRepository.worldDir(context))
                }
                if (BackupSection.REGEXSETS in sections) {
                    RegexSetRepository.ensureAllIds(context)
                    addDirectory(zip, "regexsets", RegexSetRepository.setsDir(context))
                }
                if (BackupSection.CHATS in sections) {
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
                if (BackupSection.API_CONFIG in sections) {
                    addTextEntry(zip, API_ENTRY, ApiConfigStore.exportPortable(context))
                }
                if (BackupSection.SEARCH_CONFIG in sections) {
                    addTextEntry(zip, SEARCH_ENTRY, SearchStore.exportPortable(context))
                }
                if (BackupSection.TTS_CONFIG in sections) {
                    addTextEntry(zip, TTS_ENTRY, TtsStore.exportPortable(context))
                }
                if (BackupSection.AVATAR in sections) {
                    UserProfileStore.getAvatarPath(context)?.let(::File)?.takeIf { it.isFile }?.let { avatar ->
                        addFile(zip, AVATAR_ENTRY, avatar)
                    }
                }
            }
        }

    suspend fun inspect(context: Context, input: InputStream): Set<BackupSection> =
        withContext(Dispatchers.IO) {
            withStaging(context, input) { availableSections(it) }
        }

    suspend fun import(
        context: Context,
        input: InputStream,
        sections: Set<BackupSection>,
    ): BackupImportResult = withContext(Dispatchers.IO) {
        recoverInterruptedImportInternal(context)
        require(sections.isNotEmpty()) { "No backup content selected" }
        withStaging(context, input) { staging ->
            val available = availableSections(staging)
            require(sections.all { it in available }) { "Selected content is missing from the backup" }

            // Parse every selected structured file before changing live data.
            val chatArchive = if (BackupSection.CHATS in sections) {
                json.decodeFromString<ChatArchive>(File(staging, CHAT_ENTRY).readText()).also {
                    require(it.formatVersion in 2..FORMAT_VERSION) {
                        "Unsupported backup version: ${it.formatVersion}"
                    }
                }
            } else null
            val apiConfig = if (BackupSection.API_CONFIG in sections) {
                ApiConfigStore.parsePortable(File(staging, API_ENTRY).readText())
            } else null
            val searchConfig = if (BackupSection.SEARCH_CONFIG in sections) {
                SearchStore.parsePortable(File(staging, SEARCH_ENTRY).readText())
            } else null
            val ttsConfig = if (BackupSection.TTS_CONFIG in sections) {
                TtsStore.parsePortable(File(staging, TTS_ENTRY).readText())
            } else null

            val previousApiPortable = apiConfig?.let { ApiConfigStore.exportPortable(context) }
            val previousApi = apiConfig?.let { ApiConfigStore.loadConfig(context) }
            val previousSearchPortable = searchConfig?.let { SearchStore.exportPortable(context) }
            val previousSearch = searchConfig?.let {
                SearchStore.parsePortable(requireNotNull(previousSearchPortable))
            }
            val previousTtsPortable = ttsConfig?.let { TtsStore.exportPortable(context) }
            val previousTts = ttsConfig?.let {
                TtsStore.parsePortable(requireNotNull(previousTtsPortable))
            }
            val previousGlobalVariables = chatArchive?.globalVariables?.let { GlobalVariableStore.get(context) }
            val previousSessions = if (BackupSection.CHATS in sections) ChatRepository.list(context) else null
            val previousAvatarPath = if (BackupSection.AVATAR in sections) {
                UserProfileStore.getAvatarPath(context)
            } else null

            val transactionDir = prepareRestoreJournal(
                context = context,
                staging = staging,
                sections = sections,
                previousApi = previousApiPortable,
                previousSearch = previousSearchPortable,
                previousTts = previousTtsPortable,
                previousChats = previousSessions?.let {
                    ChatArchive(sessions = it, globalVariables = previousGlobalVariables)
                },
                restoreGlobalVariables = chatArchive?.globalVariables != null,
                previousAvatarPath = previousAvatarPath,
            )
            val rollbackDir = File(transactionDir, "originals")
            val restored = mutableListOf<RestoredFile>()
            var restoredFiles = 0
            var avatarPathTouched = false
            var apiConfigTouched = false
            var searchConfigTouched = false
            var ttsConfigTouched = false
            var globalVariablesTouched = false
            var chatDatabaseTouched = false
            try {
                if (BackupSection.CHARACTERS in sections) {
                    restoredFiles += restoreDirectory(
                        File(staging, "characters"), CharacterRepository.charsDir(context),
                        File(rollbackDir, "characters"), restored,
                    )
                }
                if (BackupSection.PRESETS in sections) {
                    restoredFiles += restoreDirectory(
                        File(staging, "presets"), PresetRepository.presetsDir(context),
                        File(rollbackDir, "presets"), restored,
                    )
                }
                if (BackupSection.WORLDBOOKS in sections) {
                    restoredFiles += restoreDirectory(
                        File(staging, "worldbooks"), WorldBookRepository.worldDir(context),
                        File(rollbackDir, "worldbooks"), restored,
                    )
                }
                if (BackupSection.REGEXSETS in sections) {
                    restoredFiles += restoreDirectory(
                        File(staging, "regexsets"), RegexSetRepository.setsDir(context),
                        File(rollbackDir, "regexsets"), restored,
                    )
                }
                if (BackupSection.CHATS in sections) {
                    restoredFiles += restoreDirectory(
                        File(staging, "attachments"), AttachmentStore.dir(context),
                        File(rollbackDir, "attachments"), restored,
                    )
                }
                if (BackupSection.AVATAR in sections) {
                    val avatarTarget = File(context.filesDir, "avatars/user_avatar")
                    restoreFile(
                        File(staging, AVATAR_ENTRY),
                        avatarTarget,
                        File(rollbackDir, "avatar/user_avatar"),
                        restored,
                    )
                    avatarPathTouched = true
                    UserProfileStore.setAvatarPathSync(context, avatarTarget.absolutePath)
                    restoredFiles++
                }

                apiConfig?.let {
                    apiConfigTouched = true
                    ApiConfigStore.saveConfigSync(context, it)
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
                            ApiConfigStore.saveConfigSync(context, config)
                        })
                    }
                    if (avatarPathTouched) {
                        add(BackupRollbackStep("avatar path") {
                            UserProfileStore.setAvatarPathSync(context, previousAvatarPath)
                        })
                    }
                    restored.asReversed().forEach { record ->
                        add(BackupRollbackStep("file ${record.target.absolutePath}") {
                            rollbackFile(record)
                        })
                    }
                }
                withContext(NonCancellable) {
                    rollbackAfterFailure(e, rollbackSteps) { _, rollbackError ->
                        SafeLog.error(TAG, "backup_rollback_failed", rollbackError)
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
                transactionDir.deleteRecursively()
                throw e
            }
            me.rerere.fawntavern.data.JsonFileDir.atomicWriteText(
                File(transactionDir, RESTORE_COMMITTED),
                "committed",
            )
            if (BackupSection.CHATS in sections) runCatching { ChatRepository.collectUnusedAttachments(context) }
            transactionDir.deleteRecursively()
            BackupImportResult(restoredFiles, chatArchive?.sessions?.size ?: 0)
        }
    }

    /** Complete rollback from a restore that was interrupted after its durable prepare point. */
    suspend fun recoverInterruptedImport(context: Context) = withContext(Dispatchers.IO) {
        recoverInterruptedImportInternal(context)
    }

    fun hasInterruptedImport(context: Context): Boolean =
        restoreTransactionRoots(context).any { root ->
            root.listFiles()?.any { it.isDirectory } == true
        }

    private suspend fun recoverInterruptedImportInternal(context: Context) {
        restoreTransactionRoots(context).forEach { recoverTransactionRoot(context, it) }
    }

    private fun restoreTransactionRoots(context: Context): List<File> = listOf(
        File(context.noBackupFilesDir, RESTORE_TXN_DIR),
        // Older builds stored journals under filesDir, where Android Backup could include secrets.
        File(context.filesDir, RESTORE_TXN_DIR),
    )

    private suspend fun recoverTransactionRoot(context: Context, root: File) {
        root.listFiles()?.filter { it.isDirectory }?.forEach { transactionDir ->
            val prepared = File(transactionDir, RESTORE_PREPARED)
            if (!prepared.isFile) {
                transactionDir.deleteRecursively()
                return@forEach
            }
            if (File(transactionDir, RESTORE_COMMITTED).isFile) {
                transactionDir.deleteRecursively()
                return@forEach
            }
            val journal = json.decodeFromString<RestoreJournal>(
                File(transactionDir, RESTORE_JOURNAL).readText(),
            )

            journal.previousChats?.let { ChatRepository.replaceAll(context, it.sessions) }
            if (journal.restoreGlobalVariables) {
                GlobalVariableStore.set(context, journal.previousChats?.globalVariables.orEmpty())
            }
            journal.previousTts?.let { TtsStore.importPortable(context, TtsStore.parsePortable(it)) }
            journal.previousSearch?.let { SearchStore.importPortable(context, SearchStore.parsePortable(it)) }
            journal.previousApi?.let { ApiConfigStore.saveConfigSync(context, ApiConfigStore.parsePortable(it)) }
            if (journal.restoreAvatarPath) {
                UserProfileStore.setAvatarPathSync(context, journal.previousAvatarPath)
            }

            journal.files.asReversed().forEach { snapshot ->
                val target = journalTarget(context, snapshot.area, snapshot.name)
                if (snapshot.existed) {
                    val original = File(File(transactionDir, "originals/${snapshot.area}"), snapshot.name)
                    atomicReplaceFile(original, target)
                } else if (target.exists() && !target.delete()) {
                    throw IOException("Unable to remove partially restored file: ${target.absolutePath}")
                }
            }
            transactionDir.deleteRecursively()
        }
        root.delete()
    }

    private fun prepareRestoreJournal(
        context: Context,
        staging: File,
        sections: Set<BackupSection>,
        previousApi: String?,
        previousSearch: String?,
        previousTts: String?,
        previousChats: ChatArchive?,
        restoreGlobalVariables: Boolean,
        previousAvatarPath: String?,
    ): File {
        val transactionDir = File(
            File(context.noBackupFilesDir, RESTORE_TXN_DIR).also { it.mkdirs() },
            UUID.randomUUID().toString(),
        ).also { it.mkdirs() }
        try {
            val snapshots = buildList {
                fun capture(area: String, source: File) {
                    source.listFiles()?.filter { it.isFile }?.forEach { incoming ->
                        val target = journalTarget(context, area, incoming.name)
                        add(JournalFile(area, incoming.name, target.isFile))
                        if (target.isFile) {
                            val original = File(File(transactionDir, "originals/$area"), incoming.name)
                            original.parentFile?.mkdirs()
                            atomicReplaceFile(target, original)
                        }
                    }
                }
                if (BackupSection.CHARACTERS in sections) capture("characters", File(staging, "characters"))
                if (BackupSection.PRESETS in sections) capture("presets", File(staging, "presets"))
                if (BackupSection.WORLDBOOKS in sections) capture("worldbooks", File(staging, "worldbooks"))
                if (BackupSection.REGEXSETS in sections) capture("regexsets", File(staging, "regexsets"))
                if (BackupSection.CHATS in sections) capture("attachments", File(staging, "attachments"))
                if (BackupSection.AVATAR in sections) {
                    val avatar = File(staging, AVATAR_ENTRY)
                    if (avatar.isFile) {
                        val target = journalTarget(context, "avatar", "user_avatar")
                        add(JournalFile("avatar", "user_avatar", target.isFile))
                        if (target.isFile) {
                            val original = File(transactionDir, "originals/avatar/user_avatar")
                            original.parentFile?.mkdirs()
                            atomicReplaceFile(target, original)
                        }
                    }
                }
            }
            val journal = RestoreJournal(
                files = snapshots,
                previousApi = previousApi,
                previousSearch = previousSearch,
                previousTts = previousTts,
                previousChats = previousChats,
                restoreGlobalVariables = restoreGlobalVariables,
                restoreAvatarPath = BackupSection.AVATAR in sections,
                previousAvatarPath = previousAvatarPath,
            )
            me.rerere.fawntavern.data.JsonFileDir.atomicWriteText(
                File(transactionDir, RESTORE_JOURNAL),
                json.encodeToString(journal),
            )
            me.rerere.fawntavern.data.JsonFileDir.atomicWriteText(
                File(transactionDir, RESTORE_PREPARED),
                "prepared",
            )
            return transactionDir
        } catch (error: Exception) {
            transactionDir.deleteRecursively()
            throw error
        }
    }

    private fun journalTarget(context: Context, area: String, name: String): File = when (area) {
        "characters" -> File(CharacterRepository.charsDir(context), name)
        "presets" -> File(PresetRepository.presetsDir(context), name)
        "worldbooks" -> File(WorldBookRepository.worldDir(context), name)
        "regexsets" -> File(RegexSetRepository.setsDir(context), name)
        "attachments" -> File(AttachmentStore.dir(context), name)
        "avatar" -> File(context.filesDir, "avatars/user_avatar")
        else -> throw IllegalArgumentException("Unsupported restore area: $area")
    }

    private fun atomicReplaceFile(source: File, target: File) {
        require(source.isFile) { "Missing restore snapshot: ${source.absolutePath}" }
        target.parentFile?.mkdirs()
        val temp = File.createTempFile(".${target.name}_", ".tmp", target.parentFile)
        try {
            source.inputStream().use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            try {
                Files.move(
                    temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
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

    private fun availableSections(staging: File): Set<BackupSection> = buildSet {
        if (File(staging, "characters").listFiles()?.any { it.isFile } == true) add(BackupSection.CHARACTERS)
        if (File(staging, "presets").listFiles()?.any { it.isFile } == true) add(BackupSection.PRESETS)
        if (File(staging, "worldbooks").listFiles()?.any { it.isFile } == true) add(BackupSection.WORLDBOOKS)
        if (File(staging, "regexsets").listFiles()?.any { it.isFile } == true) add(BackupSection.REGEXSETS)
        if (File(staging, CHAT_ENTRY).isFile) add(BackupSection.CHATS)
        if (File(staging, API_ENTRY).isFile) add(BackupSection.API_CONFIG)
        if (File(staging, SEARCH_ENTRY).isFile) add(BackupSection.SEARCH_CONFIG)
        if (File(staging, TTS_ENTRY).isFile) add(BackupSection.TTS_CONFIG)
        if (File(staging, AVATAR_ENTRY).isFile) add(BackupSection.AVATAR)
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
        val roots = setOf(
            "characters", "presets", "worldbooks", "regexsets", "attachments", "data", "avatar", "chats",
        )
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
        require(parts[0] in setOf("characters", "presets", "worldbooks", "regexsets", "attachments")) {
            "Unsupported backup path: $entryName"
        }
        val fileName = parts[1]
        require(
            fileName != "." && fileName != ".." &&
                !fileName.contains('\\') && fileName.none { it.isISOControl() }
        ) { "Invalid backup path" }
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
        val original = if (target.exists()) {
            if (!rollbackFile.isFile) atomicReplaceFile(target, rollbackFile)
            rollbackFile
        } else null
        restoreLog += RestoredFile(target, original)
        atomicReplaceFile(source, target)
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
