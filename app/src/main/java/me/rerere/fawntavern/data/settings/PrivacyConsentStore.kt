package me.rerere.fawntavern.data.settings

import android.content.Context

/**
 * Records the user's consent for the current privacy-document version.
 * Keeping the version in the stored value lets a future policy revision
 * require consent again without changing the rest of the startup flow.
 */
object PrivacyConsentStore {
    private const val PREFS = "privacy_consent"
    private const val KEY_VERSION = "accepted_version"
    private const val CURRENT_VERSION = 1

    fun isAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VERSION, 0) >= CURRENT_VERSION

    fun accept(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .apply()
    }
}
