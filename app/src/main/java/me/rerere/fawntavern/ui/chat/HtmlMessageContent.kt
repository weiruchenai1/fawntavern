package me.rerere.fawntavern.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import java.util.LinkedHashMap
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode

private data class HtmlFragmentKey(
    val html: String,
)

/** LazyColumn 会回收离屏 AndroidView；缓存解析结果和高度，回看历史时首帧不塌成 1dp。 */
private object HtmlMessageCache {
    private const val MaxFragments = 6
    private const val MaxHeights = 128
    private const val MaxCachedFragmentChars = 750_000
    private val fragments = object : LinkedHashMap<HtmlFragmentKey, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HtmlFragmentKey, String>?): Boolean =
            size > MaxFragments
    }
    private val heights = object : LinkedHashMap<Int, Int>(160, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Int>?): Boolean = size > MaxHeights
    }

    @Synchronized
    fun fragment(key: HtmlFragmentKey, build: () -> String): String {
        fragments[key]?.let { return it }
        val value = build()
        if (value.length <= MaxCachedFragmentChars) fragments[key] = value
        return value
    }

    @Synchronized fun height(key: Int): Int? = heights[key]
    @Synchronized fun putHeight(key: Int, value: Int) { heights[key] = value }
}

/**
 * Browser-backed rendering for one bare HTML segment. Model-provided script, event handlers and
 * embedded browsing contexts are removed; app-owned JavaScript only reports asynchronous height.
 */
@Composable
internal fun HtmlMessageContent(
    html: String,
    textStyle: TextStyle,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
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

    val fragmentKey = remember(html) { HtmlFragmentKey(html) }
    val fragment = remember(fragmentKey) {
        HtmlMessageCache.fragment(fragmentKey) {
            runCatching { sanitizeHtml(html) }
                .getOrElse { "<pre>${Entities.escape(html)}</pre>" }
        }
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
    val heightCacheKey = remember(shell, fragment) { 31 * shell.hashCode() + fragment.hashCode() }
    var heightDp by remember(heightCacheKey) {
        mutableIntStateOf(HtmlMessageCache.height(heightCacheKey) ?: 1)
    }
    val reportHeight: (Int) -> Unit = { measured ->
        val next = measured.coerceIn(1, 20_000)
        HtmlMessageCache.putHeight(heightCacheKey, next)
        if (heightDp != next) heightDp = next
    }

    // Recreate only when the theme shell changes. Content edits update the existing page in place;
    // streaming bare HTML never reaches this composable and remains visible as Compose source text.
    key(shell) {
        AndroidView(
            factory = { context ->
                MessageWebView(
                    context = context,
                    shell = shell,
                    initialFragment = fragment,
                    onHeight = reportHeight,
                    onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
                )
            },
            update = {
                it.bind(onHeight = reportHeight, onOpenLink = { url ->
                    runCatching { uriHandler.openUri(url) }
                })
                it.updateFragment(fragment)
            },
            modifier = modifier.height(heightDp.dp),
            onReset = { it.deactivate() },
            onRelease = { it.destroySafely() },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
private class MessageWebView(
    context: Context,
    shell: String,
    initialFragment: String,
    onHeight: (Int) -> Unit,
    onOpenLink: (String) -> Unit,
) : WebView(context) {
    @Volatile private var bindingVersion = 0
    @Volatile private var active = true
    private var onHeight: (Int) -> Unit = onHeight
    private var onOpenLink: (String) -> Unit = onOpenLink
    private var ready = false
    private var latestFragment = initialFragment
    private var deliveredFragment: String? = null

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isNestedScrollingEnabled = false
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        addJavascriptInterface(HeightBridge(::dispatchHeight), "FawnHeight")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                deliveredFragment = initialFragment
                deliverLatest()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return true
                if (request.isForMainFrame && isExternalUrl(request.url)) onOpenLink(url)
                return true
            }

            @Deprecated("Deprecated in Android")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && isExternalUrl(Uri.parse(url))) onOpenLink(url)
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

    fun bind(onHeight: (Int) -> Unit, onOpenLink: (String) -> Unit) {
        val reactivated = !active
        // AndroidView.update also runs for ordinary recompositions. Advancing the version there would
        // discard a height report already posted by this same active view and leave it stuck at 1dp.
        if (reactivated) bindingVersion++
        active = true
        this.onHeight = onHeight
        this.onOpenLink = onOpenLink
        if (reactivated && ready) {
            evaluateJavascript("lastHeight=-1;reportHeight();", null)
        }
    }

    fun deactivate() {
        bindingVersion++
        active = false
    }

    private fun dispatchHeight(height: Int) {
        if (!active) return
        val version = bindingVersion
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (active && version == bindingVersion) onHeight(height)
        }
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
        removeJavascriptInterface("FawnHeight")
        stopLoading()
        destroy()
    }

    private fun isExternalUrl(uri: Uri): Boolean = uri.scheme == "http" || uri.scheme == "https"
}

private class HeightBridge(private val onHeight: (Int) -> Unit) {
    @JavascriptInterface
    fun report(height: Int) = onHeight(height)
}

/**
 * CommonMark turns four-space-indented HTML into a code block. ST-compatible model output often
 * indents whole status widgets, so accept it only when Jsoup finds HTML elements and no plain-text
 * top-level content. Fenced code is excluded by the AST caller before this check.
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

private fun sanitizeHtml(html: String): String {
    // Parse a complete document so fenced <!doctype html><html><head><style> examples retain their
    // head CSS. Jsoup may also relocate a fragment-level style tag into head while normalizing.
    val document = Jsoup.parse(html)
    document.select("script,iframe,frame,frameset,object,embed,applet,base,meta,link,form,input,textarea,select,option,button").remove()
    document.allElements.forEach { element ->
        element.attributes().asList().forEach { attribute ->
            val key = attribute.key.lowercase()
            val value = attribute.value.trim().lowercase()
            if (key.startsWith("on") || key == "srcdoc" || key == "formaction" ||
                ((key == "href" || key == "src" || key == "xlink:href") &&
                    (value.startsWith("javascript:") || value.startsWith("vbscript:") ||
                        value.startsWith("file:") || value.startsWith("content:") || value.startsWith("intent:")))) {
                element.removeAttr(attribute.key)
            }
        }
    }
    document.select("table").toList().forEach tableLoop@{ table ->
        if (table.parent()?.hasClass("fawn-table-scroll") == true) return@tableLoop
        table.wrap("<div class=\"fawn-table-scroll\"></div>")
    }
    renderHtmlTextEnhancements(document)
    // CSS imports bypass the document's image/font policy and are not needed by ST status widgets.
    val styles = document.select("style").map { style ->
        style.text(style.data().replace(Regex("""(?is)@import\s+[^;]+;?"""), ""))
        style.outerHtml()
    }
    document.select("style").remove()
    return buildString {
        styles.forEach { append(it) }
        append(document.body().html())
    }
}

private val inlineMarkdownInHtml = Regex(
    """`([^`\n]+?)`|\*\*(\S(?:[^\n]*?\S)?)\*\*|__(\S(?:[^\n]*?\S)?)__|(?<!\*)\*(\S(?:[^*\n]*?\S)?)\*(?!\*)|(?<!\w)_(\S(?:[^_\n]*?\S)?)_(?!\w)""",
)

/**
 * GFM leaves Markdown and soft line breaks inside raw HTML untouched. Preserve ST-style line
 * breaks in text-bearing nodes and render the small inline Markdown subset used by status cards.
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
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: https: http:; font-src data: https:; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<style>
:root{color-scheme:light dark}*{box-sizing:border-box}html,body{margin:0;padding:0;background:transparent;color:${textColor.css()};font-family:system-ui,-apple-system,sans-serif;font-size:${fontSizeCssPx}px;line-height:$lineHeight;letter-spacing:0;overflow-x:hidden}
#content{display:flow-root;width:100%}#content>:first-child{margin-top:0}#content>:last-child{margin-bottom:0}p{margin:.45em 0}h1,h2,h3,h4,h5,h6{line-height:1.3;margin:.7em 0 .35em}h1{font-size:1.5em}h2{font-size:1.3em}h3{font-size:1.15em}h4{font-size:1.05em}h5,h6{font-size:1em}a{color:${accent.css()}}img,video{max-width:100%;height:auto}.fawn-table-scroll{display:block;width:100%;max-width:100%;overflow-x:auto;overscroll-behavior-x:contain;-webkit-overflow-scrolling:touch}.fawn-table-scroll table{width:max-content;min-width:100%;border-collapse:collapse}.fawn-table-scroll th,.fawn-table-scroll td{border:1px solid ${outline.css()};padding:.35em .55em;white-space:normal;overflow-wrap:anywhere}blockquote{margin:.5em 0;padding-left:.8em;border-left:3px solid ${outline.css()};color:${mutedColor.css()}}
pre{margin:0;overflow-x:auto;padding:.75em;background:${surface.css()};white-space:pre;-webkit-overflow-scrolling:touch}code{font-family:monospace;font-size:.9em}
</style></head><body><div id="content"></div><script>
const content=document.getElementById('content');let heightRaf=0,lastHeight=-1;
function measureHeight(){heightRaf=0;const rect=content.getBoundingClientRect();const next=Math.max(1,Math.ceil(Math.max(rect.height,content.scrollHeight)));if(next!==lastHeight){lastHeight=next;FawnHeight.report(next)}}
function reportHeight(){if(!heightRaf)heightRaf=requestAnimationFrame(measureHeight)}
function settleHeight(){reportHeight();[32,96,180,320,500].forEach(ms=>setTimeout(reportHeight,ms))}
window.__fawnUpdate=function(html){const details=[...content.querySelectorAll('details')].map(x=>x.open);content.innerHTML=html;content.querySelectorAll('details').forEach((x,i)=>{if(i<details.length)x.open=details[i]});content.querySelectorAll('img,video').forEach(x=>{x.addEventListener('load',settleHeight,{once:true});x.addEventListener('error',settleHeight,{once:true})});settleHeight()};
content.addEventListener('toggle',settleHeight,true);content.addEventListener('transitionrun',settleHeight,true);content.addEventListener('transitionend',settleHeight,true);new ResizeObserver(reportHeight).observe(content);new MutationObserver(settleHeight).observe(content,{subtree:true,childList:true,attributes:true});
</script></body></html>
""".trimIndent()

private fun Color.css(): String = String.format("#%08X", toArgb()).let { argb ->
    "#" + argb.substring(3) + argb.substring(1, 3)
}
