package me.rerere.fawntavern.core.diagnostics

import android.util.Log
import java.util.ArrayDeque

enum class SafeLogLevel { ERROR, WARNING }

data class SafeLogEntry(
    val timestampMillis: Long,
    val level: SafeLogLevel,
    val tag: String,
    val event: String,
    val errorType: String?,
)

/** Keeps release logs free of exception messages and user-controlled values. */
object SafeLog {
    private const val MAX_ENTRIES = 200
    private val entriesLock = Any()
    private val entries = ArrayDeque<SafeLogEntry>()

    fun error(tag: String, event: String, error: Throwable? = null) {
        write(Log.ERROR, SafeLogLevel.ERROR, tag, event, error)
    }

    fun warn(tag: String, event: String, error: Throwable? = null) {
        write(Log.WARN, SafeLogLevel.WARNING, tag, event, error)
    }

    fun snapshot(): List<SafeLogEntry> = synchronized(entriesLock) { entries.toList() }

    fun clear() = synchronized(entriesLock) { entries.clear() }

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
                    tag = tag,
                    event = event,
                    errorType = error?.javaClass?.name,
                )
            )
            while (entries.size > MAX_ENTRIES) entries.removeLast()
        }
        if (BuildConfig.DEBUG && error != null) {
            Log.println(priority, tag, "$event\n${Log.getStackTraceString(error)}")
            return
        }
        val type = error?.javaClass?.name?.let { " [$it]" }.orEmpty()
        Log.println(priority, tag, event + type)
    }
}
