package me.rerere.fawntavern.data.settings

import androidx.core.content.edit

import android.content.Context
import org.json.JSONObject

/**
 * 默认模型及自定义提示词：按角色（chat / title / summary）分开记忆。
 *
 * 存储结构：SharedPreferences 里一个 JSON 对象，键为角色名、值为 {model, prompt}。
 * model 为 "providerId::modelId" 格式（空 = 回退到聊天模型）；prompt 为每个角色的自定义系统提示。
 */
object DefaultModelStore {
    private const val PREFS = "default_model"
    private const val KEY_DATA = "data"

    // ── 角色常量 ──
    const val ROLE_CHAT = "chat"
    const val ROLE_TITLE = "title"
    const val ROLE_SUMMARY = "summary"

    // ── 内建默认提示词（用户未自定义时生效） ──

    /** 标题模型默认提示词：{content} 占位符会被替换为首轮对话摘要 */
    const val DEFAULT_TITLE_PROMPT =
        "I will give you some dialogue content in the `<content>` block.\n" +
        "You need to summarize the conversation between user and assistant into a short title.\n" +
        "1. The title language should be consistent with the user's primary language\n" +
        "2. Do not use punctuation or other special symbols\n" +
        "3. Reply directly with the title\n" +
        "4. The title should be short (around 5-10 words)\n" +
        "5. Output only the title, no quotes or extra text\n\n" +
        "<content>\n" +
        "{content}\n" +
        "</content>"

    /** 摘要模型默认系统提示词（与 SummarizeExtension.COMPRESS_SYSTEM 一致） */
    const val DEFAULT_SUMMARY_PROMPT =
        "You are a summarization engine for a roleplay chat. Merge the previous summary (if any) and the new " +
        "conversation into a single, updated summary. Preserve key facts, character state, relationships, ongoing " +
        "goals and unresolved threads; drop small talk. Write in the same language as the conversation. Aim for " +
        "about {target} tokens. Output only the summary text, with no preamble."

    data class Entry(val model: String = "", val prompt: String = "")

    fun get(context: Context, role: String): Entry {
        val o = read(context).optJSONObject(role) ?: return Entry()
        return Entry(o.optString("model", ""), o.optString("prompt", ""))
    }

    fun setModel(context: Context, role: String, model: String) {
        val entry = get(context, role).copy(model = model)
        put(context, role, entry)
    }

    fun setPrompt(context: Context, role: String, prompt: String) {
        val entry = get(context, role).copy(prompt = prompt)
        put(context, role, entry)
    }

    /** 重置指定角色为默认值（清除存储的 model/prompt 键） */
    fun reset(context: Context, role: String) {
        val root = read(context)
        root.remove(role)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_DATA, root.toString()) }
    }

    /**
     * 解析模型选择：优先取 [role] 的存储模型；为空则回退到 [fallbackModel]（通常是聊天当前模型），
     * 拆成 provider + modelId；两个组件都非空才返回。
     */
    fun resolveModel(context: Context, role: String, fallbackModel: String): Pair<String, String>? {
        val spec = get(context, role).model.takeIf { it.isNotBlank() } ?: fallbackModel
        if (spec.isBlank()) return null
        val provId = spec.substringBefore("::")
        val modelId = spec.substringAfter("::", "")
        if (provId.isBlank() || modelId.isBlank()) return null
        return provId to modelId
    }

    /** 提供商删除/禁用级联：清理所有指向该提供商的模型选择 */
    fun removeProvider(context: Context, providerId: String) {
        val root = read(context)
        var changed = false
        for (role in listOf(ROLE_CHAT, ROLE_TITLE, ROLE_SUMMARY)) {
            val o = root.optJSONObject(role) ?: continue
            val m = o.optString("model", "")
            if (m.startsWith("$providerId::")) {
                o.remove("model")
                if (o.length() == 0) root.remove(role)
                changed = true
            }
        }
        if (changed) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit { putString(KEY_DATA, root.toString()) }
        }
    }

    private fun put(context: Context, role: String, entry: Entry) {
        val root = read(context)
        if (entry.model.isBlank() && entry.prompt.isBlank()) {
            root.remove(role)
        } else {
            root.put(role, JSONObject().apply {
                if (entry.model.isNotBlank()) put("model", entry.model)
                if (entry.prompt.isNotBlank()) put("prompt", entry.prompt)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_DATA, root.toString()) }
    }

    private fun read(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DATA, null)
        return try { if (raw == null) JSONObject() else JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }
}
