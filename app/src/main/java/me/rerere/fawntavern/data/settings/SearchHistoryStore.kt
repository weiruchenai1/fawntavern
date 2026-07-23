package me.rerere.fawntavern.data.settings

import android.content.Context

/** 聊天搜索历史 — SharedPreferences 持久化，最多保留 20 条 */
object SearchHistoryStore {
    private const val PREFS = "search_history"
    private const val KEY = "history"
    private const val MAX = 20

    fun getHistory(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return raw.split("|||").filter { it.isNotBlank() }
    }

    fun add(context: Context, query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val list = getHistory(context).toMutableList()
        list.remove(trimmed)
        list.add(0, trimmed)
        if (list.size > MAX) list.removeAt(list.lastIndex)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, list.joinToString("|||")).apply()
    }

    fun remove(context: Context, query: String) {
        val list = getHistory(context).toMutableList()
        list.remove(query)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, list.joinToString("|||")).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
