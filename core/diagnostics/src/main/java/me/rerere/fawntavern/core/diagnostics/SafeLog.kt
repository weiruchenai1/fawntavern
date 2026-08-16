package me.rerere.fawntavern.core.diagnostics

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

enum class SafeLogLevel { ERROR, WARNING }

data class SafeLogEntry(
    val timestampMillis: Long,
    val level: SafeLogLevel,
    val tag: String,
    val event: String,
    val errorType: String?,
)

/** Persists a bounded diagnostic history without exception messages or user-controlled values. */
object SafeLog {
    private const val MAX_ENTRIES = 200
    private const val FILE_MAGIC = 0x46544C47
    private const val FILE_VERSION = 1
    private const val DIRECTORY = "diagnostics"
    private const val LOG_FILE = "safe-system-log.bin"
    private const val MAX_TAG_CHARS = 128
    private const val MAX_EVENT_CHARS = 512
    private const val MAX_ERROR_TYPE_CHARS = 256
    private val entriesLock = Any()
    private val entries = ArrayDeque<SafeLogEntry>()
    private var storageFile: File? = null

    fun initialize(context: Context) {
        synchronized(entriesLock) {
            if (storageFile != null) return

            val pendingEntries = entries.toList()
            val file = File(File(context.noBackupFilesDir, DIRECTORY), LOG_FILE)
            val persistedEntries = runCatching { readEntries(file) }
                .onFailure { AtomicFile(file).delete() }
                .getOrDefault(emptyList())

            entries.clear()
            (pendingEntries + persistedEntries).take(MAX_ENTRIES).forEach(entries::addLast)
            storageFile = file
            if (pendingEntries.isNotEmpty()) persistLocked()
        }
    }

    fun error(tag: String, event: String, error: Throwable? = null) {
        write(Log.ERROR, SafeLogLevel.ERROR, tag, event, error)
    }

    fun warn(tag: String, event: String, error: Throwable? = null) {
        write(Log.WARN, SafeLogLevel.WARNING, tag, event, error)
    }

    fun snapshot(): List<SafeLogEntry> = synchronized(entriesLock) { entries.toList() }

    fun clear() {
        synchronized(entriesLock) {
            entries.clear()
            storageFile?.let { AtomicFile(it).delete() }
        }
    }

    fun storageSizeBytes(): Long = synchronized(entriesLock) {
        storageFile?.takeIf(File::isFile)?.length() ?: 0L
    }

    fun format(entries: List<SafeLogEntry> = snapshot()): String {
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        return buildString {
            appendLine("FawnTavern safe system log")
            appendLine("Entries: ${entries.size}")
            entries.forEach { entry ->
                append(timestampFormat.format(Date(entry.timestampMillis)))
                append(" [").append(entry.level.name).append("] ")
                append(entry.tag).append(" / ").append(entry.event)
                entry.errorType?.let { append(" / ").append(it) }
                appendLine()
            }
        }
    }

    private fun write(
        priority: Int,
        level: SafeLogLevel,
        tag: String,
        event: String,
        error: Throwable?,
    ) {
        synchronized(entriesLock) {
            entries.addFirst(
                SafeLogEntry(
                    timestampMillis = System.currentTimeMillis(),
                    level = level,
                    tag = safeField(tag, MAX_TAG_CHARS),
                    event = safeField(event, MAX_EVENT_CHARS),
                    errorType = error?.javaClass?.name?.let {
                        safeField(it, MAX_ERROR_TYPE_CHARS)
                    },
                )
            )
            while (entries.size > MAX_ENTRIES) entries.removeLast()
            persistLocked()
        }
        if (BuildConfig.DEBUG && error != null) {
            Log.println(priority, tag, "$event\n${Log.getStackTraceString(error)}")
            return
        }
        val type = error?.javaClass?.name?.let { " [$it]" }.orEmpty()
        Log.println(priority, tag, event + type)
    }

    private fun persistLocked() {
        val file = storageFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val atomicFile = AtomicFile(file)
            val output = atomicFile.startWrite()
            try {
                DataOutputStream(output).apply {
                    writeInt(FILE_MAGIC)
                    writeInt(FILE_VERSION)
                    writeInt(entries.size)
                    entries.forEach { entry ->
                        writeLong(entry.timestampMillis)
                        writeInt(entry.level.ordinal)
                        writeString(entry.tag)
                        writeString(entry.event)
                        writeBoolean(entry.errorType != null)
                        entry.errorType?.let { writeString(it) }
                    }
                    flush()
                }
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
        }
    }

    private fun readEntries(file: File): List<SafeLogEntry> {
        if (!file.isFile) return emptyList()
        return DataInputStream(BufferedInputStream(AtomicFile(file).openRead())).use { input ->
            require(input.readInt() == FILE_MAGIC) { "Invalid safe log file" }
            require(input.readInt() == FILE_VERSION) { "Unsupported safe log version" }
            val count = input.readInt()
            require(count in 0..MAX_ENTRIES) { "Invalid safe log entry count" }
            List(count) {
                val timestampMillis = input.readLong()
                val level = SafeLogLevel.entries.getOrNull(input.readInt())
                    ?: throw IllegalArgumentException("Invalid safe log level")
                val tag = input.readString(MAX_TAG_CHARS)
                val event = input.readString(MAX_EVENT_CHARS)
                val errorType = if (input.readBoolean()) {
                    input.readString(MAX_ERROR_TYPE_CHARS)
                } else {
                    null
                }
                SafeLogEntry(timestampMillis, level, tag, event, errorType)
            }
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(maxChars: Int): String {
        val byteCount = readInt()
        require(byteCount in 0..maxChars * 4) { "Invalid safe log string length" }
        val bytes = ByteArray(byteCount)
        try {
            readFully(bytes)
        } catch (error: EOFException) {
            throw IllegalArgumentException("Truncated safe log file", error)
        }
        return bytes.toString(Charsets.UTF_8).take(maxChars)
    }

    private fun safeField(value: String, maxChars: Int): String = value
        .filter { char ->
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "._-\$"
        }
        .take(maxChars)
        .ifBlank { "unknown" }
}
