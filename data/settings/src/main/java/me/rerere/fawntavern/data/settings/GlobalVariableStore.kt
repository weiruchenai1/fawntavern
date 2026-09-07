package me.rerere.fawntavern.data.settings

import me.rerere.fawntavern.data.commitChanges

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 所有聊天会话共享的持久化宏变量。 */
object GlobalVariableStore {
    private const val PREFS = "macro_global_variables"
    private const val KEY_DATA = "data"
    private val json = Json { ignoreUnknownKeys = true }

    fun observe(context: Context) = callbackFlow {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_DATA || key == null) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { get(context) }.distinctUntilChanged().flowOn(Dispatchers.IO)

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
