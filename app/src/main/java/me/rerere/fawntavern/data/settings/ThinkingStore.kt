package me.rerere.fawntavern.data.settings

import androidx.core.content.edit

import android.content.Context
import me.rerere.fawntavern.data.api.ReasoningLevel
import org.json.JSONObject

/**
 * 思考预算档位 —— 按模型（键为 "providerId::modelId"，同 ApiConfig.currentModel）分别记忆。
 *
 * 存全局单值的话，在支持思考的模型上调高档位后切到不支持的模型会直接 400；按模型存则新模型
 * 天然回到 [ReasoningLevel.AUTO]（不下发任何思考字段）。AUTO 不落盘，直接删键。
 */
object ThinkingStore {
    private const val PREFS = "thinking_budget"
    private const val KEY_LEVELS = "levels"

    fun get(context: Context, modelKey: String): ReasoningLevel {
        if (modelKey.isBlank()) return ReasoningLevel.AUTO
        return ReasoningLevel.fromName(read(context).optString(modelKey))
    }

    fun set(context: Context, modelKey: String, level: ReasoningLevel) {
        if (modelKey.isBlank()) return
        val obj = read(context)
        if (level == ReasoningLevel.AUTO) obj.remove(modelKey) else obj.put(modelKey, level.name)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_LEVELS, obj.toString()) }
    }

    private fun read(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LEVELS, null)
        return try { if (raw == null) JSONObject() else JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }
}
