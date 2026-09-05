package me.rerere.fawntavern.ui.chat

import java.util.WeakHashMap
import org.json.JSONObject

/** 只保存弱引用，把聊天事件路由到同一会话的可见 WebView。 */
internal object FrontendWebViewRegistry {
    private val views = WeakHashMap<MessageWebView, String>()

    fun register(view: MessageWebView, contextJson: String) {
        val chatId = runCatching { JSONObject(contextJson).optString("chatId") }.getOrDefault("")
        synchronized(views) { views[view] = chatId }
    }

    fun unregister(view: MessageWebView) {
        synchronized(views) { views.remove(view) }
    }

    fun dispatch(event: ChatFrontendEvent) {
        val eventChatId = runCatching { JSONObject(event.payloadJson).optString("chat_id") }.getOrDefault("")
        val targets = synchronized(views) {
            views.entries.filter { eventChatId.isBlank() || it.value == eventChatId }.map { it.key }
        }
        targets.forEach { it.dispatchFrontendEvent(event) }
    }
}

internal fun dispatchFrontendEvent(event: ChatFrontendEvent) = FrontendWebViewRegistry.dispatch(event)
