package me.rerere.fawntavern.core.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/** Stores one privacy-minimized crash report locally for explicit user sharing. */
object CrashReportStore {
    private const val DIRECTORY = "diagnostics"
    private const val REPORT_FILE = "latest-crash.txt"
    private const val TEMP_FILE_PREFIX = "crash-"
    private const val TEMP_FILE_SUFFIX = ".tmp"
    private const val MAX_CAUSE_DEPTH = 8
    private const val MAX_FRAMES_PER_CAUSE = 96
    internal const val MAX_REPORT_BYTES = 256 * 1024
    internal const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    private val TRUNCATION_MARKER = "\n[Report truncated]\n".toByteArray(Charsets.UTF_8)
    private val installed = AtomicBoolean(false)

    fun install(context: Context, appVersion: String) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        runCatching { cleanupDirectory(reportDirectory(appContext)) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 尽可能在处理器入口锁定崩溃瞬间，避免后续文件写入耗时影响报告时间。
            val crashTimeMillis = System.currentTimeMillis()
            runCatching {
                writeReport(appContext, formatReport(appVersion, thread, error, crashTimeMillis))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun readLatest(context: Context): String? {
        val directory = reportDirectory(context)
        runCatching { cleanupDirectory(directory) }
        return File(directory, REPORT_FILE).takeIf(File::isFile)?.readText()
    }

    fun clear(context: Context): Boolean {
        val file = reportFile(context)
        return !file.exists() || file.delete()
    }

    internal fun formatReport(
        appVersion: String,
        thread: Thread,
        error: Throwable,
        crashTimeMillis: Long = System.currentTimeMillis(),
    ): String = buildString {
        appendLine("FawnTavern crash report")
        appendLine("Time (device): ${deviceTimestamp(crashTimeMillis)}")
        appendLine("Time (UTC): ${utcTimestamp(crashTimeMillis)}")
        appendLine("App version: $appVersion")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Thread: ${safeThreadName(thread.name)}")
        appendLine()

        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            appendLine(if (depth == 0) current.javaClass.name else "Caused by: ${current.javaClass.name}")
            current.stackTrace.take(MAX_FRAMES_PER_CAUSE).forEach { frame ->
                appendLine("    at ${frame.className}.${frame.methodName}(${frame.fileName ?: "Unknown"}:${frame.lineNumber})")
            }
            current = current.cause
            depth++
        }
    }

    private fun writeReport(context: Context, report: String) {
        val target = reportFile(context)
        target.parentFile?.mkdirs()
        val temp = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX, target.parentFile)
        try {
            temp.writeBytes(limitReportBytes(report))
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }
        } finally {
            temp.delete()
        }
    }

    private fun reportFile(context: Context): File =
        File(reportDirectory(context), REPORT_FILE)

    private fun reportDirectory(context: Context): File =
        File(context.noBackupFilesDir, DIRECTORY)

    internal fun cleanupDirectory(directory: File, nowMillis: Long = System.currentTimeMillis()) {
        if (!directory.isDirectory) return

        val report = File(directory, REPORT_FILE)
        val cutoff = nowMillis - RETENTION_MILLIS
        if (
            report.isFile &&
            (report.length() > MAX_REPORT_BYTES || report.lastModified() in 1..<cutoff)
        ) {
            report.delete()
        }

        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(TEMP_FILE_PREFIX) && it.name.endsWith(TEMP_FILE_SUFFIX) }
            ?.forEach(File::delete)
    }

    internal fun limitReportBytes(report: String): ByteArray {
        val bytes = report.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_REPORT_BYTES) return bytes

        var contentEnd = MAX_REPORT_BYTES - TRUNCATION_MARKER.size
        while (contentEnd > 0 && (bytes[contentEnd].toInt() and 0xC0) == 0x80) {
            contentEnd--
        }
        return ByteArray(contentEnd + TRUNCATION_MARKER.size).also { result ->
            bytes.copyInto(result, endIndex = contentEnd)
            TRUNCATION_MARKER.copyInto(result, destinationOffset = contentEnd)
        }
    }

    private fun deviceTimestamp(timestampMillis: Long): String {
        val timeZone = TimeZone.getDefault()
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            .apply { this.timeZone = timeZone }
            .format(Date(timestampMillis))
        return "$timestamp (${timeZone.id})"
    }

    private fun utcTimestamp(timestampMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(timestampMillis))

    private fun safeThreadName(value: String): String = value
        .filter { it.isLetterOrDigit() || it in " ._-" }
        .take(80)
        .ifBlank { "unknown" }
}
