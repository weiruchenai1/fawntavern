package me.rerere.fawntavern.data.diagnostics

import android.content.Context
import android.os.Bundle
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.concurrent.atomic.AtomicBoolean
import me.rerere.fawntavern.BuildConfig

object RemoteDiagnostics {
    private const val PREFERENCES = "remote_diagnostics"
    private const val KEY_ENABLED = "enabled"
    private const val EVENT_APP_STARTED = "app_started"
    private const val USER_PROPERTY_BUILD_TYPE = "build_type"
    private val appStartRecorded = AtomicBoolean(false)

    fun isAvailable(context: Context): Boolean =
        BuildConfig.FIREBASE_ENABLED && FirebaseApp.initializeApp(context.applicationContext) != null

    fun isEnabled(context: Context): Boolean = isAvailable(context) &&
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun applySavedPreference(context: Context) {
        val appContext = context.applicationContext
        val enabled = isEnabled(appContext)
        applyCollectionState(appContext, enabled)
        if (enabled) recordAppStart(appContext)
    }

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val appContext = context.applicationContext
        val effectiveValue = enabled && isAvailable(appContext)
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_ENABLED, effectiveValue) }
        applyCollectionState(appContext, effectiveValue)
        return effectiveValue
    }

    private fun applyCollectionState(context: Context, enabled: Boolean) {
        if (!BuildConfig.FIREBASE_ENABLED || FirebaseApp.initializeApp(context) == null) return

        FirebaseAnalytics.getInstance(context).run {
            setAnalyticsCollectionEnabled(enabled)
            if (enabled) setUserProperty(USER_PROPERTY_BUILD_TYPE, BuildConfig.BUILD_TYPE)
        }
        FirebaseCrashlytics.getInstance().run {
            setCrashlyticsCollectionEnabled(enabled)
            if (enabled) {
                setCustomKey("build_type", BuildConfig.BUILD_TYPE)
                setCustomKey("version_name", BuildConfig.VERSION_NAME)
                log("Remote diagnostics enabled")
            } else {
                deleteUnsentReports()
            }
        }
    }

    /** Adds an explicit event so a correctly configured installation is easy to find in Analytics. */
    private fun recordAppStart(context: Context) {
        if (!appStartRecorded.compareAndSet(false, true)) return
        FirebaseAnalytics.getInstance(context).logEvent(
            EVENT_APP_STARTED,
            Bundle().apply {
                putString("build_type", BuildConfig.BUILD_TYPE)
                putString("version_name", BuildConfig.VERSION_NAME)
            },
        )
    }
}
