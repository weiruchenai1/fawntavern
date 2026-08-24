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
import me.rerere.fawntavern.data.api.Http
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode

private data class HtmlFragmentKey(
    val html: String,
    val allowContentJavaScript: Boolean,
)

private const val MinHtmlWebViewHeightDp = 120
private const val MaxHtmlWebViewHeightDp = 600

/** 浏览器渲染的 HTTPS 图片专用缓存，不依赖服务器返回的缓存头。 */
private object HtmlImageResourceCache {
    private const val MaxCacheBytes = 128L * 1024L * 1024L
    private const val CacheMaxAgeSeconds = 30L * 24L * 60L * 60L
    private val imageExtensions = setOf(
        "avif", "bmp", "gif", "heic", "heif", "ico", "jpeg", "jpg", "png", "svg", "webp",
    )

    @Volatile private var client: OkHttpClient? = null

    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET" || request.url.scheme != "https" || !isImageRequest(request)) {
            return null
        }
        val networkRequest = Request.Builder()
            .url(request.url.toString())
            .apply {
                request.requestHeaders.forEach { (name, value) ->
                    if (!name.equals("Cache-Control", ignoreCase = true) &&
                        !name.equals("Pragma", ignoreCase = true) &&
                        !name.startsWith("If-", ignoreCase = true)) {
                        header(name, value)
                    }
                }
            }
            .build()
        val response = runCatching { client(context).newCall(networkRequest).execute() }.getOrNull()
            ?: return null
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        val body = response.body
        val contentType = body.contentType()
        if (contentType?.type != "image") {
            response.close()
            return null
        }
        return WebResourceResponse(
            "${contentType.type}/${contentType.subtype}",
            null,
            body.byteStream(),
        )
    }

    private fun client(context: Context): OkHttpClient {
        client?.let { return it }
        return synchronized(this) {
            client ?: Http.client.newBuilder()
                .cache(Cache(File(context.applicationContext.cacheDir, "html_image_cache"), MaxCacheBytes))
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (response.isSuccessful && response.body.contentType()?.type == "image") {
                        response.newBuilder()
                            .removeHeader("Pragma")
                            .header("Cache-Control", "public, max-age=$CacheMaxAgeSeconds")
                            .build()
                    } else {
                        response
                    }
                }
                .build()
                .also { client = it }
        }
    }

    private fun isImageRequest(request: WebResourceRequest): Boolean {
        val acceptsImages = request.requestHeaders.entries.any { (name, value) ->
            name.equals("Accept", ignoreCase = true) && value.contains("image/", ignoreCase = true)
        }
        if (acceptsImages) return true
        val extension = request.url.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return extension in imageExtensions
    }
}

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
    chatMessagesJson: String = "[]",
    onSetInputText: (String) -> Unit = {},
    onSetChatMessage: (Int, String) -> Unit = { _, _ -> },
    onSelectChatMessageSwipe: (Int, Int) -> Unit = { _, _ -> },
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

    val fragmentKey = remember(html, allowContentJavaScript) {
        HtmlFragmentKey(stripStandaloneHtmlFence(html), allowContentJavaScript)
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
        textColor, mutedColor, surface, outline, accent, fontCssPx, lineHeight,
    ) {
        htmlShell(
            textColor = textColor,
            mutedColor = mutedColor,
            surface = surface,
            outline = outline,
            accent = accent,
            fontSizeCssPx = fontCssPx,
            lineHeight = lineHeight,
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
                    allowContentJavaScript = allowContentJavaScript,
                    chatMessagesJson = chatMessagesJson,
                    onPageHeight = onPageHeight,
                    onSetInputText = onSetInputText,
                    onSetChatMessage = onSetChatMessage,
                    onSelectChatMessageSwipe = onSelectChatMessageSwipe,
                    onOpenImage = { previewImage = it },
                    onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
                )
            },
            update = {
                it.bind(
                    chatMessagesJson = chatMessagesJson,
                    onPageHeight = onPageHeight,
                    onSetInputText = onSetInputText,
                    onSetChatMessage = onSetChatMessage,
                    onSelectChatMessageSwipe = onSelectChatMessageSwipe,
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
private class MessageWebView(
    context: Context,
    shell: String,
    initialFragment: String,
    private val allowContentJavaScript: Boolean,
    chatMessagesJson: String,
    onPageHeight: (Int) -> Unit,
    onSetInputText: (String) -> Unit,
    onSetChatMessage: (Int, String) -> Unit,
    onSelectChatMessageSwipe: (Int, Int) -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) : WebView(context) {
    @Volatile private var active = true
    @Volatile private var chatMessagesJson: String = chatMessagesJson
    private var onPageHeight: (Int) -> Unit = onPageHeight
    private var onSetInputText: (String) -> Unit = onSetInputText
    private var onSetChatMessage: (Int, String) -> Unit = onSetChatMessage
    private var onSelectChatMessageSwipe: (Int, Int) -> Unit = onSelectChatMessageSwipe
    private var onOpenImage: (String) -> Unit = onOpenImage
    private var onOpenLink: (String) -> Unit = onOpenLink
    private var ready = false
    private var latestFragment = initialFragment
    private var deliveredFragment: String? = null
    private var lastTouchY = 0f
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
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
                showImage = { source -> dispatchToMain { this@MessageWebView.onOpenImage(source) } },
            ),
            "FawnBridge",
        )
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                return request?.let { HtmlImageResourceCache.intercept(context, it) }
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
        val bootstrap = "window.__fawnUpdate(" + org.json.JSONObject.quote(initialFragment) + ");"
        val initialPage = shell.replace("</body>", "<script>$bootstrap</script></body>")
        loadDataWithBaseURL("https://appassets.androidplatform.net/", initialPage, "text/html", "utf-8", null)
    }

    fun updateFragment(fragment: String) {
        latestFragment = fragment
        deliverLatest()
    }

    fun bind(
        chatMessagesJson: String,
        onPageHeight: (Int) -> Unit,
        onSetInputText: (String) -> Unit,
        onSetChatMessage: (Int, String) -> Unit,
        onSelectChatMessageSwipe: (Int, Int) -> Unit,
        onOpenImage: (String) -> Unit,
        onOpenLink: (String) -> Unit,
    ) {
        active = true
        this.chatMessagesJson = chatMessagesJson
        this.onPageHeight = onPageHeight
        this.onSetInputText = onSetInputText
        this.onSetChatMessage = onSetChatMessage
        this.onSelectChatMessageSwipe = onSelectChatMessageSwipe
        this.onOpenImage = onOpenImage
        this.onOpenLink = onOpenLink
    }

    fun deactivate() {
        active = false
        // LazyColumn 可能在系统处理焦点时移除此 AndroidView，因此分离前清除焦点和待处理回调，
        // 避免 Android 10 在 CompositionImpl.drainPendingModificationsForCompositionLocked 中崩溃。
        mainHandler.removeCallbacksAndMessages(null)
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
            "window.__fawnUpdate(${org.json.JSONObject.quote(latestFragment)});",
            null,
        )
    }

    fun destroySafely() {
        deactivate()
        removeJavascriptInterface("FawnBridge")
        destroy()
    }

    private fun isExternalUrl(uri: Uri): Boolean = uri.scheme == "http" || uri.scheme == "https"

    private fun isEmbeddedUrl(uri: Uri): Boolean = when (uri.scheme) {
        "http", "https", "data", "blob", "about" -> true
        else -> false
    }
}

private class TavernBridge(
    private val messages: () -> String,
    private val reportHeight: (Int) -> Unit,
    private val setInput: (String) -> Unit,
    private val setMessage: (Int, String) -> Unit,
    private val selectSwipe: (Int, Int) -> Unit,
    private val showImage: (String) -> Unit,
) {
    @JavascriptInterface
    fun getChatMessages(): String = messages()

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

/**
 * CommonMark 会把缩进四个空格的 HTML 识别为代码块。ST 兼容输出常会整体缩进状态组件，因此
 * 只有 Jsoup 找到 HTML 元素且顶层没有普通文本时才接受；AST 调用方会提前排除围栏代码。
 */
internal fun isBareHtmlFragment(source: String): Boolean = runCatching {
    val body = Jsoup.parseBodyFragment(source).body()
    var hasElement = false
    body.childNodes().all { node ->
        when (node) {
            is Element -> {
                hasElement = true
                true
            }
            is TextNode -> node.wholeText.isBlank()
            else -> true // Comments, declarations and other non-text HTML nodes are safe here.
        }
    } && hasElement
}.getOrDefault(false)

internal fun stripStandaloneHtmlFence(source: String): String {
    return extractStandaloneHtmlDocument(source) ?: source
}

internal fun stripStandaloneHtmlFenceLines(source: String): String {
    return extractFencedHtmlMessage(source) ?: source
}

internal fun extractFencedHtmlMessage(source: String): String? {
    val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
    val hasHtmlFence = Regex("(?im)^\\s*```(?:html|css)\\s*$").containsMatchIn(normalized)
    val hasHtml = Regex("(?is)<(?:!doctype|html|head|body|style|script|iframe|div|section|article|details|form)\\b")
        .containsMatchIn(normalized)
    if (!hasHtmlFence || !hasHtml) return null
    val resourceFence = Regex(
        "(?ims)^[ \\t]*```(html|css|javascript|js)[ \\t]*\\n(.*?)^[ \\t]*```[ \\t]*(?=\\n|$)",
    )
    val transformed = resourceFence.replace(normalized) { match ->
        val language = match.groupValues[1].lowercase()
        val body = match.groupValues[2].removeSuffix("\n")
        when (language) {
            "css" -> wrapFencedResource(body, "style")
            "javascript", "js" -> wrapFencedResource(body, "script")
            else -> body
        }
    }
    // 兼容使用无类型起始围栏并在后面追加有类型区段的旧角色卡。
    return transformed.lineSequence()
        .filterNot { it.trim() == "```" }
        .joinToString("\n")
}

private fun wrapFencedResource(source: String, tag: String): String {
    val alreadyWrapped = Regex("(?is)^\\s*<$tag\\b").containsMatchIn(source)
    return if (alreadyWrapped) source else "<$tag>\n$source</$tag>"
}

internal fun extractStandaloneHtmlDocument(source: String): String? {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
    val first = lines.indexOfFirst { it.isNotBlank() }
    val last = lines.indexOfLast { it.isNotBlank() }
    if (first < 0 || last <= first) return null
    if (!lines[first].trim().matches(Regex("```(?:html|css)?", RegexOption.IGNORE_CASE)) ||
        lines[last].trim() != "```") return null
    lines.removeAt(last)
    lines.removeAt(first)
    return lines.joinToString("\n")
}

private fun fixedWebViewHeightDp(fragment: String): Int {
    val document = Jsoup.parseBodyFragment(fragment)
    val textLines = document.text().length.coerceAtLeast(1) / 56 + 1
    val structuralLines = document.select("p,div,section,article,header,footer,li,tr,pre,iframe").size
    val sourceLines = fragment.count { it == '\n' } + 1
    val estimatedLines = maxOf(textLines, structuralLines, sourceLines.coerceAtMost(20))
    return (96 + estimatedLines * 24).coerceIn(
        MinHtmlWebViewHeightDp,
        MaxHtmlWebViewHeightDp,
    )
}

internal fun sanitizeHtml(html: String, allowContentJavaScript: Boolean): String {
    // 按完整文档解析，保留围栏 <!doctype html><html><head><style> 示例中的头部 CSS；
    // Jsoup 在规范化时也可能把片段级 style 标签移动到 head。
    val document = Jsoup.parse(replaceViewportHeightUnits(html))
    document.select("frame,frameset,object,embed,applet,base,meta").remove()
    sanitizeElementAttributes(document, allowContentJavaScript)
    if (!allowContentJavaScript) {
        document.select("script").remove()
    }
    document.select("link").forEach { link ->
        if (!link.attr("rel").equals("stylesheet", ignoreCase = true)) link.remove()
    }
    // 有高度上限的 WebView 不会把折叠区域下方的图片暴露给浏览器视口，因此强制立即加载，
    // 让角色卡图库同时开始请求，而不是逐张出现。
    document.select("img").forEach { image ->
        image.attr("loading", "eager")
        image.attr("decoding", "async")
    }
    document.select("iframe").forEach { iframe ->
        if (!allowContentJavaScript) iframe.removeAttr("src")
        iframe.removeAttr("height")
        iframe.attr("width", "100%")
        iframe.attr("scrolling", "no")
        iframe.attr("style", mergeCss(iframe.attr("style"), "width:100%;border:0;overflow:hidden"))
        if (iframe.hasAttr("srcdoc")) {
            iframe.attr("srcdoc", iframeDocument(iframe.attr("srcdoc"), allowContentJavaScript))
        }
    }
    document.select("table").toList().forEach tableLoop@{ table ->
        if (table.parent()?.hasClass("fawn-table-scroll") == true) return@tableLoop
        table.wrap("<div class=\"fawn-table-scroll\"></div>")
    }
    renderHtmlTextEnhancements(document)
    val styles = document.select("style").map { style ->
        style.outerHtml()
    }
    document.select("style").remove()
    val styleLinks = document.head().select("link[rel=stylesheet]").map(Element::outerHtml)
    document.head().select("link[rel=stylesheet]").remove()
    val headScripts = document.head().select("script").map(Element::outerHtml)
    document.head().select("script").remove()
    return buildString {
        styles.forEach { append(it) }
        styleLinks.forEach { append(it) }
        headScripts.forEach { append(it) }
        append(document.body().html())
    }
}

private fun sanitizeElementAttributes(
    document: Document,
    allowContentJavaScript: Boolean,
) {
    document.allElements.forEach { element ->
        element.attributes().asList().forEach { attribute ->
            val key = attribute.key.lowercase()
            val value = attribute.value.trim().lowercase()
            val blockedScriptScheme = !allowContentJavaScript && value.startsWith("javascript:")
            if ((!allowContentJavaScript && key.startsWith("on")) ||
                key == "formaction" ||
                ((key == "href" || key == "src" || key == "xlink:href") &&
                    (blockedScriptScheme || value.startsWith("vbscript:") ||
                        value.startsWith("file:") || value.startsWith("content:") || value.startsWith("intent:")))) {
                element.removeAttr(attribute.key)
            }
        }
    }
}

private fun mergeCss(existing: String, required: String): String =
    listOf(existing.trim().trimEnd(';'), required).filter { it.isNotBlank() }.joinToString(";")

private fun iframeDocument(source: String, allowContentJavaScript: Boolean): String {
    val document = Jsoup.parse(replaceViewportHeightUnits(source))
    sanitizeElementAttributes(document, allowContentJavaScript)
    if (!allowContentJavaScript) {
        document.select("script").remove()
    }
    document.head().prependElement("meta").attr("name", "viewport")
        .attr("content", "width=device-width,initial-scale=1")
    document.head().prependElement("style").text(
        "*,*::before,*::after{box-sizing:border-box}html,body{margin:0;padding:0;max-width:100%;overflow:hidden!important}",
    )
    document.head().prependElement("script").attr("data-fawn-runtime", "iframe").append(iframeRuntime)
    document.outputSettings().prettyPrint(false)
    return document.outerHtml()
}

private val viewportHeightUnit = Regex(
    """(?i)((?:min-|max-)?height\s*:\s*)([^;{}]*?)(\d+(?:\.\d+)?)vh(?=\s*[;}])""",
)

internal fun replaceViewportHeightUnits(source: String): String = viewportHeightUnit.replace(source) { match ->
    val amount = match.groupValues[3].toDoubleOrNull() ?: return@replace match.value
    val replacement = if (amount == 100.0) {
        "var(--TH-viewport-height)"
    } else {
        "calc(var(--TH-viewport-height) * ${amount / 100.0})"
    }
    match.groupValues[1] + match.groupValues[2] + replacement
}

private val iframeRuntime = """
(function(){
let resizeRaf=0;
function viewport(){const h=window.screen?.availHeight||window.screen?.height||window.innerHeight;document.documentElement.style.setProperty('--TH-viewport-height',h+'px')}
function resize(){resizeRaf=0;const b=document.body,d=document.documentElement;if(!b||!d)return;const h=Math.max(1,Math.ceil(Math.max(b.scrollHeight,b.offsetHeight,d.scrollHeight,d.offsetHeight)));if(frameElement)frameElement.style.setProperty('height',h+'px','important')}
function schedule(){if(!resizeRaf)resizeRaf=requestAnimationFrame(resize)}
function notifyParent(){schedule();requestAnimationFrame(()=>{try{window.parent.__fawnStructureChanged()}catch(_){}})}
function start(){viewport();schedule();new ResizeObserver(schedule).observe(document.body);new MutationObserver(schedule).observe(document.body,{subtree:true,childList:true,attributes:true});document.querySelectorAll('img,video').forEach(x=>{x.addEventListener('load',schedule);x.addEventListener('error',schedule)});document.addEventListener('click',event=>{const image=event.target&&event.target.closest?event.target.closest('img'):null;if(image){event.preventDefault();try{window.parent.__fawnOpenImage(image.currentSrc||image.src)}catch(_){}}notifyParent()},true);document.addEventListener('toggle',notifyParent,true)}
window.addEventListener('resize',()=>{viewport();schedule()});if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start,{once:true});else start();
})();
""".trimIndent()

private val inlineMarkdownInHtml = Regex(
    """`([^`\n]+?)`|\*\*(\S(?:[^\n]*?\S)?)\*\*|__(\S(?:[^\n]*?\S)?)__|(?<!\*)\*(\S(?:[^*\n]*?\S)?)\*(?!\*)|(?<!\w)_(\S(?:[^_\n]*?\S)?)_(?!\w)""",
)

/**
 * GFM 不处理裸 HTML 内的 Markdown 和软换行。这里保留含文本节点中的 ST 风格换行，并渲染
 * 状态卡常用的少量行内 Markdown。
 */
private fun renderHtmlTextEnhancements(document: Document) {
    val excluded = setOf("style", "script", "pre", "code", "textarea")
    document.body().allElements.toList().forEach elementLoop@{ element ->
        val isExcluded = generateSequence(element) { it.parent() }
            .any { it.normalName() in excluded }
        if (isExcluded) return@elementLoop
        element.textNodes().toList().forEach textLoop@{ textNode ->
            val source = textNode.wholeText.replace("\r\n", "\n").replace('\r', '\n')
            val hasLineBreak = source.contains('\n') && source.isNotBlank()
            val hasInlineMarkdown = inlineMarkdownInHtml.containsMatchIn(source)
            if (!hasLineBreak && !hasInlineMarkdown) return@textLoop
            val rendered = buildString(source.length + 16) {
                fun appendText(value: String) {
                    val escaped = Entities.escape(value)
                    escaped.forEach { char ->
                        if (char == '\n') append("<br>") else append(char)
                    }
                }
                var end = 0
                inlineMarkdownInHtml.findAll(source).forEach { match ->
                    appendText(source.substring(end, match.range.first))
                    val (tag, content) = when {
                        match.groups[1] != null -> "code" to match.groupValues[1]
                        match.groups[2] != null -> "strong" to match.groupValues[2]
                        match.groups[3] != null -> "strong" to match.groupValues[3]
                        match.groups[4] != null -> "em" to match.groupValues[4]
                        else -> "em" to match.groupValues[5]
                    }
                    append('<').append(tag).append('>')
                    append(Entities.escape(content))
                    append("</").append(tag).append('>')
                    end = match.range.last + 1
                }
                appendText(source.substring(end))
            }
            textNode.before(rendered)
            textNode.remove()
        }
    }
}

private fun htmlShell(
    textColor: Color,
    mutedColor: Color,
    surface: Color,
    outline: Color,
    accent: Color,
    fontSizeCssPx: Float,
    lineHeight: Float,
): String = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1"><meta name="referrer" content="no-referrer">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: blob: https: http:; media-src data: blob: https: http:; font-src data: https: http:; style-src 'unsafe-inline' https: http:; script-src 'unsafe-inline' 'unsafe-eval' data: blob: https: http:; connect-src data: blob: https: http: wss: ws:; worker-src data: blob: https: http:; frame-src 'self' data: blob: https: http:; form-action 'none'">
<style>
:root{color-scheme:light dark}*{box-sizing:border-box}html,body{margin:0;padding:0;background:transparent;color:${textColor.css()};font-family:system-ui,-apple-system,sans-serif;font-size:${fontSizeCssPx}px;line-height:$lineHeight;letter-spacing:0;overflow-x:hidden;overflow-y:auto}
#content{display:flow-root;width:100%}#content>:first-child{margin-top:0}#content>:last-child{margin-bottom:0}p{margin:.45em 0}h1,h2,h3,h4,h5,h6{line-height:1.3;margin:.7em 0 .35em}h1{font-size:1.5em}h2{font-size:1.3em}h3{font-size:1.15em}h4{font-size:1.05em}h5,h6{font-size:1em}a{color:${accent.css()}}img,video{max-width:100%;height:auto}.fawn-table-scroll{display:block;width:100%;max-width:100%;overflow-x:auto;overscroll-behavior-x:contain;-webkit-overflow-scrolling:touch}.fawn-table-scroll table{width:max-content;min-width:100%;border-collapse:collapse}.fawn-table-scroll th,.fawn-table-scroll td{border:1px solid ${outline.css()};padding:.35em .55em;white-space:normal;overflow-wrap:anywhere}blockquote{margin:.5em 0;padding-left:.8em;border-left:3px solid ${outline.css()};color:${mutedColor.css()}}
pre{margin:0;overflow-x:auto;padding:.75em;background:${surface.css()};white-space:pre;-webkit-overflow-scrolling:touch}code{font-family:monospace;font-size:.9em}iframe{display:block;width:100%;border:0;overflow:hidden}#send_textarea{display:none!important}
</style></head><body><textarea id="send_textarea" aria-hidden="true"></textarea><div id="content"></div><script>
const content=document.getElementById('content'),sendTextarea=document.getElementById('send_textarea');
let pageHeightRaf=0;
function syncViewportHeight(){const h=window.screen?.availHeight||window.screen?.height||window.innerHeight;document.documentElement.style.setProperty('--TH-viewport-height',h+'px')}
syncViewportHeight();window.addEventListener('resize',syncViewportHeight);
function reportPageHeight(){pageHeightRaf=0;const h=Math.max(1,Math.ceil(Math.max(content.scrollHeight,content.getBoundingClientRect().height)));FawnBridge.reportPageHeight(h)}
function schedulePageHeight(){if(!pageHeightRaf)pageHeightRaf=requestAnimationFrame(reportPageHeight)}
window.__fawnStructureChanged=function(){requestAnimationFrame(schedulePageHeight)};
function bridgeInput(){FawnBridge.setInputText(sendTextarea.value)}
sendTextarea.addEventListener('input',bridgeInput);sendTextarea.addEventListener('change',bridgeInput);
window.setInputText=function(value){const text=String(value??'');sendTextarea.value=text;FawnBridge.setInputText(text)};
window.getChatMessages=function(range){let messages=[];try{messages=JSON.parse(FawnBridge.getChatMessages())}catch(_){}if(range===undefined||range===null)return messages;if(typeof range==='number'){const i=range<0?messages.length+range:range;return i>=0&&i<messages.length?[messages[i]]:[]}const match=String(range).match(/^(-?\d+)(?:-(-?\d+))?$/);if(!match)return messages;const index=x=>{const n=Number(x);return n<0?messages.length+n:n};const start=index(match[1]),end=index(match[2]??match[1]);return messages.slice(Math.max(0,Math.min(start,end)),Math.min(messages.length,Math.max(start,end)+1))};
window.setChatMessage=function(fields,messageId,options){const id=Number(messageId);const swipeId=options&&Number(options.swipe_id);if(Number.isInteger(swipeId)&&swipeId>=0){FawnBridge.selectChatMessageSwipe(id,swipeId);return Promise.resolve()}const value=typeof fields==='string'?fields:(fields&&fields.message);if(value!==undefined)FawnBridge.setChatMessage(id,String(value));return Promise.resolve()};
window.__fawnOpenImage=function(source){if(source)FawnBridge.openImage(String(source))};
async function activateScripts(root){
  for(const old of [...root.querySelectorAll('script')]){
    if(!old.isConnected)continue;
    const next=document.createElement('script');[...old.attributes].forEach(a=>next.setAttribute(a.name,a.value));next.text=old.textContent;
    const type=(next.getAttribute('type')||'').toLowerCase(),wait=!next.hasAttribute('async')&&(next.hasAttribute('src')||type==='module');
    const settled=wait?new Promise(resolve=>{next.addEventListener('load',resolve,{once:true});next.addEventListener('error',resolve,{once:true})}):null;
    if(next.hasAttribute('src')&&!next.hasAttribute('async'))next.async=false;
    old.replaceWith(next);if(settled)await settled;
  }
}
const observedFrames=new WeakSet();
function fitFrame(frame){
  if(!frame||observedFrames.has(frame))return;observedFrames.add(frame);
  const setup=()=>{try{const doc=frame.contentDocument;if(!doc)return;let raf=0,first=true;const resize=()=>{raf=0;const b=doc.body,d=doc.documentElement;if(!b||!d)return;const h=Math.max(1,Math.ceil(Math.max(b.scrollHeight,b.offsetHeight,d.scrollHeight,d.offsetHeight)));frame.style.setProperty('height',h+'px','important');if(first){first=false;schedulePageHeight()}};const schedule=()=>{if(!raf)raf=requestAnimationFrame(resize)};schedule();new ResizeObserver(schedule).observe(doc.body);new MutationObserver(schedule).observe(doc.body,{subtree:true,childList:true,attributes:true});doc.querySelectorAll('img,video').forEach(x=>{x.addEventListener('load',schedule);x.addEventListener('error',schedule)});doc.addEventListener('click',()=>requestAnimationFrame(()=>{resize();schedulePageHeight()}),true);doc.addEventListener('toggle',()=>requestAnimationFrame(()=>{resize();schedulePageHeight()}),true)}catch(_){}};
  frame.addEventListener('load',setup);setup();
}
function activateFrames(root){root.querySelectorAll('iframe').forEach(fitFrame)}
const observedMedia=new WeakSet();
function watchMedia(root){
  const media=[];if(root.matches&&root.matches('img,video'))media.push(root);if(root.querySelectorAll)media.push(...root.querySelectorAll('img,video'));
  media.forEach(item=>{if(observedMedia.has(item))return;observedMedia.add(item);item.addEventListener('load',schedulePageHeight);item.addEventListener('loadedmetadata',schedulePageHeight);item.addEventListener('error',schedulePageHeight);if(item.complete||item.readyState>0)schedulePageHeight()})
}
new MutationObserver(records=>{records.forEach(record=>{record.addedNodes.forEach(node=>{if(node.nodeType!==1)return;if(node.matches&&node.matches('iframe'))fitFrame(node);if(node.querySelectorAll){activateFrames(node);watchMedia(node)}})})}).observe(content,{subtree:true,childList:true});
content.addEventListener('click',event=>{const image=event.target&&event.target.closest?event.target.closest('img'):null;if(image){event.preventDefault();window.__fawnOpenImage(image.currentSrc||image.src)}requestAnimationFrame(schedulePageHeight)},true);content.addEventListener('toggle',()=>requestAnimationFrame(schedulePageHeight),true);
let contentRevision=0;
window.__fawnUpdate=async function(html){const revision=++contentRevision,details=[...content.querySelectorAll('details')].map(x=>x.open);content.innerHTML=html;content.querySelectorAll('details').forEach((x,i)=>{if(i<details.length)x.open=details[i]});activateFrames(content);watchMedia(content);await activateScripts(content);if(revision!==contentRevision)return;activateFrames(content);watchMedia(content);schedulePageHeight()};
</script></body></html>
""".trimIndent()

private fun Color.css(): String = String.format("#%08X", toArgb()).let { argb ->
    "#" + argb.substring(3) + argb.substring(1, 3)
}
