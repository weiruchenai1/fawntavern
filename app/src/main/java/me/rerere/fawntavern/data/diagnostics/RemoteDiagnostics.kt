package me.rerere.fawntavern.data.diagnostics

import android.content.Context
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import me.rerere.fawntavern.BuildConfig

object RemoteDiagnostics {
    private const val PREFERENCES = "remote_diagnostics"
    private const val KEY_ENABLED = "enabled"

    fun isAvailable(context: Context): Boolean =
        BuildConfig.FIREBASE_ENABLED && FirebaseApp.initializeApp(context.applicationContext) != null

    fun isEnabled(context: Context): Boolean = isAvailable(context) &&
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun applySavedPreference(context: Context) {
        applyCollectionState(context.applicationContext, isEnabled(context))
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

        FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enabled)
        FirebaseCrashlytics.getInstance().run {
            setCrashlyticsCollectionEnabled(enabled)
            if (!enabled) deleteUnsentReports()
        }
    }
}
