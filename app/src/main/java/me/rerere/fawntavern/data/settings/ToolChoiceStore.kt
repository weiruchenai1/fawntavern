package me.rerere.fawntavern.data.settings

import android.content.Context
import androidx.core.content.edit
import me.rerere.fawntavern.data.api.ToolChoice

/** 按模型分别保存由应用执行的函数调用策略。 */
object ToolChoiceStore {
    private const val PREFS = "tool_choice"

    fun get(context: Context, modelKey: String): ToolChoice {
        if (modelKey.isBlank()) return ToolChoice.AUTO
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(modelKey, null)
        return ToolChoice.entries.firstOrNull { it.name == value } ?: ToolChoice.AUTO
    }

    fun set(context: Context, modelKey: String, choice: ToolChoice) {
        if (modelKey.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(modelKey, choice.name)
        }
    }
}
