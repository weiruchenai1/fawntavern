package me.rerere.stapp.extension.builtin

import me.rerere.stapp.extension.Extension
import me.rerere.stapp.extension.ExtensionInfo
import me.rerere.stapp.extension.QuickReply
import me.rerere.stapp.extension.QuickReplyProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * 官方扩展：快捷回复（简单版）。在输入框上方提供可点击按钮，点击=插入输入框或直接发送。
 * 配置（ExtensionStore.getConfig(ID)）是一组 {label, text, send} 的 JSON 数组；默认空（不显示）。
 */
object QuickReplyExtension : Extension, QuickReplyProvider {

    const val ID = "builtin.quickreply"

    override val info = ExtensionInfo(
        id = ID,
        name = "Quick Reply",
        description = "在输入框上方提供可点击的快捷回复按钮（插入或直接发送）。",
    )

    override fun quickReplies(config: String): List<QuickReply> = parseConfig(config)

    fun parseConfig(blob: String): List<QuickReply> {
        if (blob.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(blob)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val label = o.optString("label")
                val text = o.optString("text")
                if (label.isBlank() && text.isBlank()) null
                else QuickReply(label = label.ifBlank { text }, text = text, send = o.optBoolean("send", false))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeConfig(list: List<QuickReply>): String {
        val arr = JSONArray()
        list.forEach { qr ->
            arr.put(JSONObject().put("label", qr.label).put("text", qr.text).put("send", qr.send))
        }
        return arr.toString()
    }
}
