package me.rerere.fawntavern.data.settings

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/** 角色、预设和脚本作用域的 Tavern Helper JSON 状态。 */
object FrontendVariableStore {
    private const val PREFS = "frontend_scoped_variables"
    private const val MAX_JSON_BYTES = 256 * 1024

    fun load(context: Context, scope: String, ownerId: String): String {
        if (ownerId.isBlank()) return "{}"
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(scope, ownerId), null)
            ?.takeIf(::isObjectJson)
            ?: "{}"
    }

    fun save(context: Context, scope: String, ownerId: String, json: String) {
        require(ownerId.isNotBlank())
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES)
        require(isObjectJson(json))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            if (json == "{}") remove(key(scope, ownerId)) else putString(key(scope, ownerId), json)
        }
    }

    private fun key(scope: String, ownerId: String): String = "$scope:$ownerId"
    private fun isObjectJson(value: String): Boolean = runCatching { JSONObject(value) }.isSuccess
}
