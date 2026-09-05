package me.rerere.fawntavern.ui.chat

import android.webkit.JavascriptInterface

internal class TavernBridge(
    private val messages: () -> String,
    private val context: () -> String,
    private val variables: (String) -> String,
    private val reportHeight: (Int) -> Unit,
    private val setInput: (String) -> Unit,
    private val setMessage: (Int, String) -> Unit,
    private val selectSwipe: (Int, Int) -> Unit,
    private val replaceVariables: (String, Map<String, String>) -> Unit,
    private val rpcCall: (String, String, String) -> Unit,
    private val cancelRpc: (String) -> Unit,
    private val showImage: (String) -> Unit,
) {
    @JavascriptInterface
    fun getChatMessages(): String = messages()

    @JavascriptInterface
    fun getFrontendContext(): String = context()

    @JavascriptInterface
    fun getVariables(scope: String): String = variables(scope.lowercase())

    @JavascriptInterface
    fun replaceVariables(scope: String, json: String) {
        val normalized = scope.lowercase()
        if (normalized != "chat" && normalized != "global") return
        decodeFrontendVariables(json)?.let { replaceVariables(normalized, it) }
    }

    @JavascriptInterface
    fun call(requestId: String, method: String, paramsJson: String) = rpcCall(requestId, method, paramsJson)

    @JavascriptInterface
    fun cancel(requestId: String) = cancelRpc(requestId)

    @JavascriptInterface
    fun reportPageHeight(height: Int) = reportHeight(height)

    @JavascriptInterface
    fun setInputText(value: String) = setInput(value)

    @JavascriptInterface
    fun setChatMessage(index: Int, value: String) = setMessage(index, value)

    @JavascriptInterface
    fun selectChatMessageSwipe(index: Int, swipeId: Int) = selectSwipe(index, swipeId)

    @JavascriptInterface
    fun openImage(source: String) = showImage(source)
}
