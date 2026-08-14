package me.rerere.fawntavern.core.diagnostics

import android.util.Log

/** Keeps release logs free of exception messages and user-controlled values. */
object SafeLog {
    fun error(tag: String, event: String, error: Throwable? = null) {
        write(Log.ERROR, tag, event, error)
    }

    fun warn(tag: String, event: String, error: Throwable? = null) {
        write(Log.WARN, tag, event, error)
    }

    private fun write(priority: Int, tag: String, event: String, error: Throwable?) {
        if (BuildConfig.DEBUG && error != null) {
            Log.println(priority, tag, "$event\n${Log.getStackTraceString(error)}")
            return
        }
        val type = error?.javaClass?.name?.let { " [$it]" }.orEmpty()
        Log.println(priority, tag, event + type)
    }
}
