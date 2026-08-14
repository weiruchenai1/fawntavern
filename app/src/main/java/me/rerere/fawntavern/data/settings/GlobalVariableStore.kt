package me.rerere.fawntavern.data.settings

import me.rerere.fawntavern.data.commitChanges

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persistent macro variables shared by every chat session. */
object GlobalVariableStore {
    private const val PREFS = "macro_global_variables"
    private const val KEY_DATA = "data"
    private val json = Json { ignoreUnknownKeys = true }

    fun get(context: Context): Map<String, String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DATA, null)
            ?: return emptyMap()
        return try { json.decodeFromString<Map<String, String>>(raw) } catch (_: Exception) { emptyMap() }
    }

    fun set(context: Context, variables: Map<String, String>) {
        val value = if (variables.isEmpty()) null else json.encodeToString(variables)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).commitChanges { putString(KEY_DATA, value) }) {
            "Unable to persist global macro variables"
        }
    }
}
