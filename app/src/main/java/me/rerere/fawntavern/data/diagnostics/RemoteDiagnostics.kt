package me.rerere.fawntavern.data.diagnostics

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tencent.bugly.crashreport.CrashReport
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import me.rerere.fawntavern.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object RemoteDiagnostics {
    enum class Backend {
        BUGLY,
        FIREBASE,
        UNAVAILABLE,
    }

    private const val PREFERENCES = "remote_diagnostics"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_IP_COUNTRY = "ip_country"
    private const val KEY_IP_COUNTRY_UPDATED_AT = "ip_country_updated_at"
    private const val EVENT_APP_STARTED = "app_started"
    private const val USER_PROPERTY_BUILD_TYPE = "build_type"
    private const val IP_COUNTRY_URL = "https://www.cloudflare.com/cdn-cgi/trace"
    private const val IP_COUNTRY_CACHE_MILLIS = 24L * 60 * 60 * 1000
    private val appStartRecorded = AtomicBoolean(false)
    private val buglyInitialized = AtomicBoolean(false)
    private val ipLookupInFlight = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val regionClient = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    fun backend(context: Context): Backend = selectBackend(
        region = DiagnosticsRegionDetector.detect(
            localeCountry = Locale.getDefault().country,
            timeZoneId = TimeZone.getDefault().id,
            ipCountry = preferences(context).getString(KEY_IP_COUNTRY, null),
        ),
        buglyAvailable = BuildConfig.BUGLY_ENABLED && BuildConfig.BUGLY_APP_ID.isNotBlank(),
        firebaseAvailable = BuildConfig.FIREBASE_ENABLED &&
            FirebaseApp.initializeApp(context.applicationContext) != null,
    )

    fun isAvailable(context: Context): Boolean = backend(context) != Backend.UNAVAILABLE

    fun isEnabled(context: Context): Boolean = isAvailable(context) && savedEnabled(context)

    fun applySavedPreference(context: Context) {
        val appContext = context.applicationContext
        val enabled = savedEnabled(appContext)
        applyCollectionState(appContext, enabled)
        if (enabled) recordAppStart(appContext)
        if (enabled) refreshIpCountryIfNeeded(appContext)
    }

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val appContext = context.applicationContext
        val effectiveValue = enabled && isAvailable(appContext)
        preferences(appContext).edit { putBoolean(KEY_ENABLED, effectiveValue) }
        applyCollectionState(appContext, effectiveValue)
        if (effectiveValue) refreshIpCountryIfNeeded(appContext, force = true)
        return effectiveValue
    }

    private fun applyCollectionState(context: Context, enabled: Boolean) {
        when (backend(context)) {
            Backend.BUGLY -> {
                applyFirebaseCollectionState(context, false)
                applyBuglyCollectionState(context, enabled)
            }
            Backend.FIREBASE -> {
                applyBuglyCollectionState(context, false)
                applyFirebaseCollectionState(context, enabled)
            }
            Backend.UNAVAILABLE -> {
                applyBuglyCollectionState(context, false)
                applyFirebaseCollectionState(context, false)
            }
        }
    }

    private fun applyFirebaseCollectionState(context: Context, enabled: Boolean) {
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

    @Suppress("DEPRECATION") // Bugly provides no replacement for explicitly disabling device ID collection.
    private fun applyBuglyCollectionState(context: Context, enabled: Boolean) {
        if (!BuildConfig.BUGLY_ENABLED || BuildConfig.BUGLY_APP_ID.isBlank()) return

        if (!enabled) {
            CrashReport.closeCrashReport()
            CrashReport.enableBugly(false)
            return
        }

        CrashReport.enableBugly(true)
        if (buglyInitialized.compareAndSet(false, true)) {
            CrashReport.enableObtainId(context, false)
            CrashReport.setCollectPrivacyInfo(context, false)
            val strategy = CrashReport.UserStrategy(context).apply {
                appVersion = BuildConfig.VERSION_NAME
                appChannel = "mainland-china"
                appPackageName = context.packageName
            }
            CrashReport.initCrashReport(
                context,
                BuildConfig.BUGLY_APP_ID,
                false,
                strategy,
            )
            CrashReport.putUserData(context, "build_type", BuildConfig.BUILD_TYPE)
            CrashReport.putUserData(context, "version_name", BuildConfig.VERSION_NAME)
        } else {
            CrashReport.startCrashReport()
        }
    }

    /** Adds an explicit event so a correctly configured installation is easy to find in Analytics. */
    private fun recordAppStart(context: Context) {
        if (backend(context) != Backend.FIREBASE) return
        if (!appStartRecorded.compareAndSet(false, true)) return
        FirebaseAnalytics.getInstance(context).logEvent(
            EVENT_APP_STARTED,
            Bundle().apply {
                putString("build_type", BuildConfig.BUILD_TYPE)
                putString("version_name", BuildConfig.VERSION_NAME)
            },
        )
    }

    private fun refreshIpCountryIfNeeded(context: Context, force: Boolean = false) {
        val preferences = preferences(context)
        val updatedAt = preferences.getLong(KEY_IP_COUNTRY_UPDATED_AT, 0L)
        if (!force && System.currentTimeMillis() - updatedAt < IP_COUNTRY_CACHE_MILLIS) return
        if (!ipLookupInFlight.compareAndSet(false, true)) return

        regionClient.newCall(
            Request.Builder()
                .url(IP_COUNTRY_URL)
                .build(),
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                ipLookupInFlight.set(false)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val country = response.use { result ->
                        if (!result.isSuccessful) null
                        else DiagnosticsRegionDetector.parseCloudflareCountry(result.body.string())
                    }
                    if (country != null) {
                        preferences.edit {
                            putString(KEY_IP_COUNTRY, country)
                            putLong(KEY_IP_COUNTRY_UPDATED_AT, System.currentTimeMillis())
                        }
                        mainHandler.post {
                            val enabled = savedEnabled(context)
                            applyCollectionState(context, enabled)
                            if (enabled) recordAppStart(context)
                        }
                    }
                } finally {
                    ipLookupInFlight.set(false)
                }
            }
        })
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun savedEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ENABLED, true)

    internal fun selectBackend(
        region: DiagnosticsRegion,
        buglyAvailable: Boolean,
        firebaseAvailable: Boolean,
    ): Backend = when (region) {
        DiagnosticsRegion.MAINLAND_CHINA -> if (buglyAvailable) Backend.BUGLY else Backend.UNAVAILABLE
        DiagnosticsRegion.OTHER -> if (firebaseAvailable) Backend.FIREBASE else Backend.UNAVAILABLE
        DiagnosticsRegion.UNKNOWN -> Backend.UNAVAILABLE
    }
}
