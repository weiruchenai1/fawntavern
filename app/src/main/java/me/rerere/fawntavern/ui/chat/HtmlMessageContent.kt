package me.rerere.fawntavern.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import java.io.File
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.fawntavern.data.api.Http
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import org.json.JSONObject
import org.json.JSONTokener

private data class HtmlFragmentKey(
    val html: String,
    val allowContentJavaScript: Boolean,
)

internal const val MinHtmlWebViewHeightDp = 120
internal const val MaxHtmlWebViewHeightDp = 600
internal const val FrontendAssetOrigin = "https://appassets.androidplatform.net/frontend/"
private const val FrontendRpcTimeoutMs = 15_000L
private const val FrontendRpcMaxPayloadBytes = 256 * 1024

internal typealias FrontendRpcCall = suspend (method: String, paramsJson: String) -> String

/** LazyColumn 会回收屏幕外的 AndroidView，因此这里只缓存解析后的 HTML 片段。 */
private object HtmlMessageCache {
    private const val MaxFragments = 6
    private const val MaxCachedFragmentChars = 750_000
    private val fragments = object : LinkedHashMap<HtmlFragmentKey, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HtmlFragmentKey, String>?): Boolean =
            size > MaxFragments
    }
    @Synchronized
    fun fragment(key: HtmlFragmentKey, build: () -> String): String {
        fragments[key]?.let { return it }
        val value = build()
        if (value.length <= MaxCachedFragmentChars) fragments[key] = value
        return value
    }

}

/**
 * 使用浏览器渲染单个 HTML 片段。Compose 使用稳定且有上限的高度，文档、图片和嵌入框架
 * 在 WebView 内部完成布局与滚动。
 */
@Composable
internal fun HtmlMessageContent(
    html: String,
    textStyle: TextStyle,
    modifier: Modifier,
    allowContentJavaScript: Boolean,
    isStreaming: Boolean = false,
    chatMessagesJson: String = "[]",
    frontendContextJson: String = "{}",
    localVariablesJson: String = "{}",
    globalVariablesJson: String = "{}",
    onSetInputText: (String) -> Unit = {},
    onSetChatMessage: (Int, String) -> Unit = { _, _ -> },
    onSelectChatMessageSwipe: (Int, Int) -> Unit = { _, _ -> },
    onReplaceVariables: (String, Map<String, String>) -> Unit = { _, _ -> },
    rpcCall: FrontendRpcCall = { method, _ -> error("Frontend RPC method is unavailable: $method") },
) {
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
    var previewImage by remember { mutableStateOf<String?>(null) }
    val textColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val outline = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val fontCssPx = with(density) {
        (if (textStyle.fontSize.isSpecified) textStyle.fontSize.toPx() else 16.dp.toPx()) /
            this.density
    }
    val lineHeight = if (textStyle.lineHeight.isSpecified && textStyle.fontSize.isSpecified) {
        textStyle.lineHeight.value / textStyle.fontSize.value
    } else 1.5f

    val expandedHtml = remember(html, frontendContextJson) {
        expandFrontendRuntimeMacros(html, frontendContextJson)
    }
    val fragmentKey = remember(expandedHtml, allowContentJavaScript) {
        HtmlFragmentKey(stripStandaloneHtmlFence(expandedHtml), allowContentJavaScript)
    }
    val fragment = remember(fragmentKey) {
        HtmlMessageCache.fragment(fragmentKey) {
            runCatching {
                sanitizeHtml(fragmentKey.html, fragmentKey.allowContentJavaScript)
            }
                .getOrElse { "<pre>${Entities.escape(fragmentKey.html)}</pre>" }
        }
    }
    // 先按源码估算高度，页面会在初始布局、交互以及图片或媒体异步加载后回报实际高度。
    var webViewHeightDp by remember(fragmentKey) {
        mutableIntStateOf(fixedWebViewHeightDp(fragmentKey.html))
    }
    val onPageHeight: (Int) -> Unit = { height ->
        webViewHeightDp = height.coerceIn(
            MinHtmlWebViewHeightDp,
            MaxHtmlWebViewHeightDp,
        )
    }
    val shell = remember(
        textColor, mutedColor, surface, outline, accent, fontCssPx, lineHeight, allowContentJavaScript,
    ) {
        htmlShell(
            textColor = textColor,
            mutedColor = mutedColor,
            surface = surface,
            outline = outline,
            accent = accent,
            fontSizeCssPx = fontCssPx,
            lineHeight = lineHeight,
            allowContentJavaScript = allowContentJavaScript,
        )
    }
    // 内容编辑直接更新现有页面；主题或 JavaScript 策略变化时重建页面，避免旧文档安装的定时器
    // 和监听器继续存活。即使内容 JavaScript 关闭，内部布局脚本仍保持启用。
    key(shell, allowContentJavaScript) {
        AndroidView(
            factory = { context ->
                MessageWebView(
                    context = context,
                    shell = shell,
                    initialFragment = fragment,
                    streaming = isStreaming,
                    allowContentJavaScript = allowContentJavaScript,
                    chatMessagesJson = chatMessagesJson,
                    frontendContextJson = frontendContextJson,
                    localVariablesJson = localVariablesJson,
                    globalVariablesJson = globalVariablesJson,
                    onPageHeight = onPageHeight,
                    onSetInputText = onSetInputText,
                    onSetChatMessage = onSetChatMessage,
                    onSelectChatMessageSwipe = onSelectChatMessageSwipe,
                    onReplaceVariables = onReplaceVariables,
                    rpcCall = rpcCall,
                    onOpenImage = { previewImage = it },
                    onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
                )
            },
            update = {
                it.bind(
                    chatMessagesJson = chatMessagesJson,
                    frontendContextJson = frontendContextJson,
                    localVariablesJson = localVariablesJson,
                    globalVariablesJson = globalVariablesJson,
                    streaming = isStreaming,
                    onPageHeight = onPageHeight,
                    onSetInputText = onSetInputText,
                    onSetChatMessage = onSetChatMessage,
                    onSelectChatMessageSwipe = onSelectChatMessageSwipe,
                    onReplaceVariables = onReplaceVariables,
                    rpcCall = rpcCall,
                    onOpenImage = { previewImage = it },
                    onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
                )
                it.updateFragment(fragment)
            },
            modifier = modifier
                .heightIn(
                    min = MinHtmlWebViewHeightDp.dp,
                    max = MaxHtmlWebViewHeightDp.dp,
                )
                .height(webViewHeightDp.dp),
            onReset = { it.deactivate() },
            onRelease = { it.destroySafely() },
        )
    }
    previewImage?.let { image ->
        ImagePreviewDialog(model = image, onDismiss = { previewImage = null })
    }
}

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class MessageWebView(
    context: Context,
    shell: String,
    initialFragment: String,
    streaming: Boolean,
    private val allowContentJavaScript: Boolean,
    chatMessagesJson: String,
    frontendContextJson: String,
    localVariablesJson: String,
    globalVariablesJson: String,
    onPageHeight: (Int) -> Unit,
    onSetInputText: (String) -> Unit,
    onSetChatMessage: (Int, String) -> Unit,
    onSelectChatMessageSwipe: (Int, Int) -> Unit,
    onReplaceVariables: (String, Map<String, String>) -> Unit,
    rpcCall: FrontendRpcCall,
    onOpenImage: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) : WebView(context) {
    @Volatile private var active = true
    @Volatile private var chatMessagesJson: String = chatMessagesJson
    @Volatile private var frontendContextJson: String = frontendContextJson
    @Volatile private var localVariablesJson: String = localVariablesJson
    @Volatile private var globalVariablesJson: String = globalVariablesJson
    private var onPageHeight: (Int) -> Unit = onPageHeight
    private var onSetInputText: (String) -> Unit = onSetInputText
    private var onSetChatMessage: (Int, String) -> Unit = onSetChatMessage
    private var onSelectChatMessageSwipe: (Int, Int) -> Unit = onSelectChatMessageSwipe
    private var onReplaceVariables: (String, Map<String, String>) -> Unit = onReplaceVariables
    private var rpcCall: FrontendRpcCall = rpcCall
    private var onOpenImage: (String) -> Unit = onOpenImage
    private var onOpenLink: (String) -> Unit = onOpenLink
    private var ready = false
    private var latestFragment = initialFragment
    private var deliveredFragment: String? = null
    private var streaming = streaming
    private var lastTouchY = 0f
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rpcScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val rpcJobs = ConcurrentHashMap<String, Job>()

    init {
        FrontendWebViewRegistry.register(this, frontendContextJson)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isNestedScrollingEnabled = false
        // LazyColumn 移除已聚焦的平台视图时会把焦点交还 AndroidComposeView；Android 10 会在
        // Compose 应用变更期间同步触发越界组合并导致崩溃。消息 HTML 仍可触摸和运行脚本，
        // 但不参与平台焦点链。
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        isFocusable = false
        isFocusableInTouchMode = false
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = allowContentJavaScript
            loadsImagesAutomatically = true
            blockNetworkImage = false
            blockNetworkLoads = false
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
        }
        addJavascriptInterface(
            TavernBridge(
                messages = { this.chatMessagesJson },
                context = { this.frontendContextJson },
                variables = { scope -> if (scope == "global") this.globalVariablesJson else this.localVariablesJson },
                reportHeight = { height ->
                    dispatchToMain { this@MessageWebView.onPageHeight(height) }
                },
                setInput = { value -> dispatchToMain { this@MessageWebView.onSetInputText(value) } },
                setMessage = { index, value ->
                    dispatchToMain { this@MessageWebView.onSetChatMessage(index, value) }
                },
                selectSwipe = { index, swipeId ->
                    dispatchToMain { this@MessageWebView.onSelectChatMessageSwipe(index, swipeId) }
                },
                replaceVariables = { scope, values ->
                    dispatchToMain { this@MessageWebView.onReplaceVariables(scope, values) }
                },
                rpcCall = { requestId, method, params ->
                    dispatchToMain { runRpc(requestId, method, params) }
                },
                cancelRpc = { requestId -> dispatchToMain { cancelRpc(requestId) } },
                showImage = { source -> dispatchToMain { this@MessageWebView.onOpenImage(source) } },
            ),
            "FawnBridge",
        )
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                return request?.let { FrontendAssetResources.intercept(context, it) }
                    ?: request?.let { HtmlImageResourceCache.intercept(context, it) }
                    ?: super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                deliveredFragment = initialFragment
                deliverLatest()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                val url = uri.toString()
                if (!request.isForMainFrame) {
                    return !(allowContentJavaScript && isEmbeddedUrl(uri))
                }
                if (allowContentJavaScript && uri.scheme == "javascript") return false
                if (isExternalUrl(uri)) onOpenLink(url)
                return true
            }

            @Deprecated("Deprecated in Android")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = url?.toUri() ?: return true
                if (allowContentJavaScript && uri.scheme == "javascript") return false
                if (isExternalUrl(uri)) onOpenLink(url)
                return true
            }
        }
        val bootstrap = "window.__fawnUpdate(" + org.json.JSONObject.quote(initialFragment) + ",false);"
        val initialPage = shell.replace("</body>", "<script>$bootstrap</script></body>")
        loadDataWithBaseURL("https://appassets.androidplatform.net/", initialPage, "text/html", "utf-8", null)
    }

    fun updateFragment(fragment: String) {
        latestFragment = fragment
        deliverLatest()
    }

    fun bind(
        chatMessagesJson: String,
        frontendContextJson: String,
        localVariablesJson: String,
        globalVariablesJson: String,
        streaming: Boolean,
        onPageHeight: (Int) -> Unit,
        onSetInputText: (String) -> Unit,
        onSetChatMessage: (Int, String) -> Unit,
        onSelectChatMessageSwipe: (Int, Int) -> Unit,
        onReplaceVariables: (String, Map<String, String>) -> Unit,
        rpcCall: FrontendRpcCall,
        onOpenImage: (String) -> Unit,
        onOpenLink: (String) -> Unit,
    ) {
        active = true
        this.chatMessagesJson = chatMessagesJson
        this.frontendContextJson = frontendContextJson
        FrontendWebViewRegistry.register(this, frontendContextJson)
        this.localVariablesJson = localVariablesJson
        this.globalVariablesJson = globalVariablesJson
        this.streaming = streaming
        this.onPageHeight = onPageHeight
        this.onSetInputText = onSetInputText
        this.onSetChatMessage = onSetChatMessage
        this.onSelectChatMessageSwipe = onSelectChatMessageSwipe
        this.onReplaceVariables = onReplaceVariables
        this.rpcCall = rpcCall
        this.onOpenImage = onOpenImage
        this.onOpenLink = onOpenLink
        if (ready && allowContentJavaScript) {
            evaluateJavascript("window.__fawnCompatibilityContextChanged&&window.__fawnCompatibilityContextChanged();", null)
        }
    }

    fun deactivate() {
        active = false
        FrontendWebViewRegistry.unregister(this)
        // LazyColumn 可能在系统处理焦点时移除此 AndroidView，因此分离前清除焦点和待处理回调，
        // 避免 Android 10 在 CompositionImpl.drainPendingModificationsForCompositionLocked 中崩溃。
        mainHandler.removeCallbacksAndMessages(null)
        rpcJobs.values.forEach { it.cancel() }
        rpcJobs.clear()
        stopLoading()
        clearFocus()
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        isFocusable = false
        isFocusableInTouchMode = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun dispatchToMain(block: () -> Unit) {
        if (!active) return
        mainHandler.post { if (active) block() }
    }

    private fun runRpc(requestId: String, method: String, paramsJson: String) {
        if (!allowContentJavaScript || requestId.length !in 1..80 ||
            !method.matches(Regex("^[a-z][a-z0-9_.-]{0,79}$")) ||
            paramsJson.toByteArray(Charsets.UTF_8).size > FrontendRpcMaxPayloadBytes) {
            resolveRpc(requestId, false, "invalid-request")
            return
        }
        rpcJobs.remove(requestId)?.cancel()
        rpcJobs[requestId] = rpcScope.launch {
            val result = runCatching {
                withTimeout(FrontendRpcTimeoutMs) { rpcCall(method, paramsJson) }
            }
            rpcJobs.remove(requestId)
            if (result.exceptionOrNull() is CancellationException) return@launch
            result.fold(
                onSuccess = { payload ->
                    if (payload.toByteArray(Charsets.UTF_8).size > FrontendRpcMaxPayloadBytes) {
                        resolveRpc(requestId, false, "result-too-large")
                    } else {
                        resolveRpc(requestId, true, payload)
                    }
                },
                onFailure = { error -> resolveRpc(requestId, false, error.message.orEmpty().take(300)) },
            )
        }
    }

    private fun cancelRpc(requestId: String) {
        rpcJobs.remove(requestId)?.cancel()
    }

    private fun resolveRpc(requestId: String, ok: Boolean, payload: String) {
        if (!active) return
        evaluateJavascript(
            "window.__fawnResolve&&window.__fawnResolve(" +
                JSONObject.quote(requestId) + "," + ok + "," + JSONObject.quote(payload) + ");",
            null,
        )
    }

    fun dispatchFrontendEvent(event: ChatFrontendEvent) {
        if (!active || !ready || !allowContentJavaScript) return
        evaluateJavascript(
            "window.__fawnEmitHostEvent&&window.__fawnEmitHostEvent(" +
                JSONObject.quote(event.type) + "," + JSONObject.quote(event.payloadJson) + "," + event.sequence + ");",
            null,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                parent?.requestDisallowInterceptTouchEvent(
                    canScrollVertically(-1) || canScrollVertically(1),
                )
            }
            MotionEvent.ACTION_MOVE -> {
                val fingerDelta = event.y - lastTouchY
                val canConsume = when {
                    fingerDelta > 0f -> canScrollVertically(-1)
                    fingerDelta < 0f -> canScrollVertically(1)
                    else -> canScrollVertically(-1) || canScrollVertically(1)
                }
                parent?.requestDisallowInterceptTouchEvent(canConsume)
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(event)
    }

    private fun deliverLatest() {
        if (!ready || latestFragment == deliveredFragment) return
        deliveredFragment = latestFragment
        evaluateJavascript(
            "window.__fawnUpdate(${org.json.JSONObject.quote(latestFragment)},${streaming});",
            null,
        )
    }

    fun destroySafely() {
        deactivate()
        rpcScope.cancel()
        removeJavascriptInterface("FawnBridge")
        destroy()
    }

    private fun isExternalUrl(uri: Uri): Boolean = uri.scheme == "http" || uri.scheme == "https"

    private fun isEmbeddedUrl(uri: Uri): Boolean = when (uri.scheme) {
        "http", "https", "data", "blob", "about" -> true
        else -> false
    }
}
