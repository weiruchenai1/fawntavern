package me.rerere.fawntavern.data.settings

import androidx.core.content.edit

import android.content.Context
import org.json.JSONObject

/**
 * 按角色名记忆最近一次从主页面模型选择器中选择的模型。
 * 切换角色时自动恢复，实现模型选择的角色级隔离。
 *
 * 存储结构：JSON 对象，键为角色名，值为 "providerId::modelId"。
 */
object CharacterModelStore {
    private const val PREFS = "character_model"
    private const val KEY_DATA = "data"

    fun get(context: Context, characterName: String): String {
        if (characterName.isBlank()) return ""
        return read(context).optString(characterName, "")
    }

    fun set(context: Context, characterName: String, model: String) {
        if (characterName.isBlank()) return
        val root = read(context)
        if (model.isBlank()) root.remove(characterName)
        else root.put(characterName, model)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_DATA, root.toString()) }
    }

    private fun read(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DATA, null)
        return try { if (raw == null) JSONObject() else JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }
}
