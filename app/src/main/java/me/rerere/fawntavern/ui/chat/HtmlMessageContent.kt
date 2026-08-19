package me.rerere.fawntavern.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.core.net.toUri
import java.util.LinkedHashMap
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode

private data class HtmlFragmentKey(
    val html: String,
)

private const val MinHtmlWebViewHeightDp = 120
private const val MaxHtmlWebViewHeightDp = 600

/** LazyColumn recycles off-screen AndroidViews; only the parsed fragment is worth caching. */
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
 * Browser-backed rendering for one HTML segment. Compose receives a stable bounded height while
 * documents, images, and embedded frames lay themselves out and scroll inside the WebView.
 */
@Composable
internal fun HtmlMessageContent(
    html: String,
    textStyle: TextStyle,
    modifier: Modifier,
    chatMessagesJson: String = "[]",
    onSetInputText: (String) -> Unit = {},
    onSetChatMessage: (Int, String) -> Unit = { _, _ -> },
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

    val fragmentKey = remember(html) { HtmlFragmentKey(stripStandaloneHtmlFence(html)) }
    val fragment = remember(fragmentKey) {
        HtmlMessageCache.fragment(fragmentKey) {
            runCatching { sanitizeHtml(fragmentKey.html) }
                .getOrElse { "<pre>${Entities.escape(fragmentKey.html)}</pre>" }
        }
    }
    // Start from a source estimate. The whole page may resize this only after initial layout or a
    // user interaction (for example details expand/collapse); image load events never report here.
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
    // Recreate only when the theme shell changes. Content edits update the existing page in place;
    // streaming bare HTML never reaches this composable and remains visible as Compose source text.
    key(shell) {
        AndroidView(
            factory = { context ->
                MessageWebView(
                    context = context,
                    shell = shell,
                    initialFragment = fragment,
                    chatMessagesJson = chatMessagesJson,
                    onPageHeight = onPageHeight,
                    onSetInputText = onSetInputText,
                    onSetChatMessage = onSetChatMessage,
                    onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
                )
            },
            update = {
                it.bind(
                    chatMessagesJson = chatMessagesJson,
                    onPageHeight = onPageHeight,
                    onSetInputText = onSetInputText,
                    onSetChatMessage = onSetChatMessage,
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
}

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
private class MessageWebView(
    context: Context,
    shell: String,
    initialFragment: String,
    chatMessagesJson: String,
    onPageHeight: (Int) -> Unit,
    onSetInputText: (String) -> Unit,
    onSetChatMessage: (Int, String) -> Unit,
    onOpenLink: (String) -> Unit,
) : WebView(context) {
    @Volatile private var active = true
    @Volatile private var chatMessagesJson: String = chatMessagesJson
    private var onPageHeight: (Int) -> Unit = onPageHeight
    private var onSetInputText: (String) -> Unit = onSetInputText
    private var onSetChatMessage: (Int, String) -> Unit = onSetChatMessage
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
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
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
            ),
            "FawnBridge",
        )
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
                if (url != null && isExternalUrl(url.toUri())) onOpenLink(url)
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
        onOpenLink: (String) -> Unit,
    ) {
        active = true
        this.chatMessagesJson = chatMessagesJson
        this.onPageHeight = onPageHeight
        this.onSetInputText = onSetInputText
        this.onSetChatMessage = onSetChatMessage
        this.onOpenLink = onOpenLink
    }

    fun deactivate() {
        active = false
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
        parent?.requestDisallowInterceptTouchEvent(false)
        removeJavascriptInterface("FawnBridge")
        stopLoading()
        destroy()
    }

    private fun isExternalUrl(uri: Uri): Boolean = uri.scheme == "http" || uri.scheme == "https"
}

private class TavernBridge(
    private val messages: () -> String,
    private val reportHeight: (Int) -> Unit,
    private val setInput: (String) -> Unit,
    private val setMessage: (Int, String) -> Unit,
) {
    @JavascriptInterface
    fun getChatMessages(): String = messages()

    @JavascriptInterface
    fun reportPageHeight(height: Int) = reportHeight(height)

    @JavascriptInterface
    fun setInputText(value: String) = setInput(value)

    @JavascriptInterface
    fun setChatMessage(index: Int, value: String) = setMessage(index, value)
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
    return normalized.lineSequence()
        .filterNot { it.trim().matches(Regex("```(?:html|css)?", RegexOption.IGNORE_CASE)) }
        .joinToString("\n")
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

private fun sanitizeHtml(html: String): String {
    // Parse a complete document so fenced <!doctype html><html><head><style> examples retain their
    // head CSS. Jsoup may also relocate a fragment-level style tag into head while normalizing.
    val document = Jsoup.parse(replaceViewportHeightUnits(html))
    document.select("frame,frameset,object,embed,applet,base,meta,link").remove()
    document.allElements.forEach { element ->
        element.attributes().asList().forEach { attribute ->
            val key = attribute.key.lowercase()
            val value = attribute.value.trim().lowercase()
            if (key == "formaction" ||
                ((key == "href" || key == "src" || key == "xlink:href") &&
                    (value.startsWith("javascript:") || value.startsWith("vbscript:") ||
                        value.startsWith("file:") || value.startsWith("content:") || value.startsWith("intent:")))) {
                element.removeAttr(attribute.key)
            }
        }
    }
    document.select("script[src]").remove()
    document.select("iframe").forEach { iframe ->
        iframe.removeAttr("src")
        iframe.removeAttr("height")
        iframe.removeAttr("sandbox")
        iframe.attr("width", "100%")
        iframe.attr("scrolling", "no")
        iframe.attr("style", mergeCss(iframe.attr("style"), "width:100%;border:0;overflow:hidden"))
        if (iframe.hasAttr("srcdoc")) {
            iframe.attr("srcdoc", iframeDocument(iframe.attr("srcdoc")))
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
    val headScripts = document.head().select("script:not([src])").map(Element::outerHtml)
    document.head().select("script:not([src])").remove()
    return buildString {
        styles.forEach { append(it) }
        headScripts.forEach { append(it) }
        append(document.body().html())
    }
}

private fun mergeCss(existing: String, required: String): String =
    listOf(existing.trim().trimEnd(';'), required).filter { it.isNotBlank() }.joinToString(";")

private fun iframeDocument(source: String): String {
    val document = Jsoup.parse(replaceViewportHeightUnits(source))
    document.select("script[src]").remove()
    document.head().prependElement("meta").attr("name", "viewport")
        .attr("content", "width=device-width,initial-scale=1")
    document.head().prependElement("style").text(
        "*,*::before,*::after{box-sizing:border-box}html,body{margin:0;padding:0;max-width:100%;overflow:hidden!important}",
    )
    document.head().prependElement("script").attr("data-fawn-runtime", "iframe").append(iframeRuntime)
    document.outputSettings().prettyPrint(false)
    return document.outerHtml()
}

private val minHeightVh = Regex(
    """(?i)(min-height\s*:\s*)([^;{}]*?)(\d+(?:\.\d+)?)vh(?=\s*[;}])""",
)

internal fun replaceViewportHeightUnits(source: String): String = minHeightVh.replace(source) { match ->
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
function viewport(){document.documentElement.style.setProperty('--TH-viewport-height',(window.parent.innerHeight||window.innerHeight)+'px')}
function resize(){resizeRaf=0;const b=document.body,d=document.documentElement;if(!b||!d)return;const h=Math.max(1,Math.ceil(Math.max(b.scrollHeight,b.offsetHeight,d.scrollHeight,d.offsetHeight)));if(frameElement)frameElement.style.setProperty('height',h+'px','important')}
function schedule(){if(!resizeRaf)resizeRaf=requestAnimationFrame(resize)}
function notifyParent(){schedule();requestAnimationFrame(()=>{try{window.parent.__fawnStructureChanged()}catch(_){}})}
function start(){viewport();schedule();new ResizeObserver(schedule).observe(document.body);new MutationObserver(schedule).observe(document.body,{subtree:true,childList:true,attributes:true});document.querySelectorAll('img,video').forEach(x=>{x.addEventListener('load',schedule);x.addEventListener('error',schedule)});document.addEventListener('click',notifyParent,true);document.addEventListener('toggle',notifyParent,true)}
window.addEventListener('resize',()=>{viewport();schedule()});if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start,{once:true});else start();
})();
""".trimIndent()

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
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: https: http:; font-src data: https:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-src 'self' data: blob:; form-action 'none'">
<style>
:root{color-scheme:light dark}*{box-sizing:border-box}html,body{margin:0;padding:0;background:transparent;color:${textColor.css()};font-family:system-ui,-apple-system,sans-serif;font-size:${fontSizeCssPx}px;line-height:$lineHeight;letter-spacing:0;overflow-x:hidden;overflow-y:auto}
#content{display:flow-root;width:100%}#content>:first-child{margin-top:0}#content>:last-child{margin-bottom:0}p{margin:.45em 0}h1,h2,h3,h4,h5,h6{line-height:1.3;margin:.7em 0 .35em}h1{font-size:1.5em}h2{font-size:1.3em}h3{font-size:1.15em}h4{font-size:1.05em}h5,h6{font-size:1em}a{color:${accent.css()}}img,video{max-width:100%;height:auto}.fawn-table-scroll{display:block;width:100%;max-width:100%;overflow-x:auto;overscroll-behavior-x:contain;-webkit-overflow-scrolling:touch}.fawn-table-scroll table{width:max-content;min-width:100%;border-collapse:collapse}.fawn-table-scroll th,.fawn-table-scroll td{border:1px solid ${outline.css()};padding:.35em .55em;white-space:normal;overflow-wrap:anywhere}blockquote{margin:.5em 0;padding-left:.8em;border-left:3px solid ${outline.css()};color:${mutedColor.css()}}
pre{margin:0;overflow-x:auto;padding:.75em;background:${surface.css()};white-space:pre;-webkit-overflow-scrolling:touch}code{font-family:monospace;font-size:.9em}iframe{display:block;width:100%;border:0;overflow:hidden}#send_textarea{display:none!important}
</style></head><body><textarea id="send_textarea" aria-hidden="true"></textarea><div id="content"></div><script>
const content=document.getElementById('content'),sendTextarea=document.getElementById('send_textarea');
let pageHeightRaf=0;
function reportPageHeight(){pageHeightRaf=0;const h=Math.max(1,Math.ceil(Math.max(content.scrollHeight,content.getBoundingClientRect().height)));FawnBridge.reportPageHeight(h)}
function schedulePageHeight(){if(!pageHeightRaf)pageHeightRaf=requestAnimationFrame(reportPageHeight)}
window.__fawnStructureChanged=function(){requestAnimationFrame(schedulePageHeight)};
function bridgeInput(){FawnBridge.setInputText(sendTextarea.value)}
sendTextarea.addEventListener('input',bridgeInput);sendTextarea.addEventListener('change',bridgeInput);
window.setInputText=function(value){const text=String(value??'');sendTextarea.value=text;FawnBridge.setInputText(text)};
window.getChatMessages=function(range){let messages=[];try{messages=JSON.parse(FawnBridge.getChatMessages())}catch(_){}if(range===undefined||range===null)return messages;if(typeof range==='number'){const i=range<0?messages.length+range:range;return i>=0&&i<messages.length?[messages[i]]:[]}const match=String(range).match(/^(-?\d+)(?:-(-?\d+))?$/);if(!match)return messages;const index=x=>{const n=Number(x);return n<0?messages.length+n:n};const start=index(match[1]),end=index(match[2]??match[1]);return messages.slice(Math.max(0,Math.min(start,end)),Math.min(messages.length,Math.max(start,end)+1))};
window.setChatMessage=function(fields,messageId){const value=typeof fields==='string'?fields:(fields&&fields.message);if(value!==undefined)FawnBridge.setChatMessage(Number(messageId),String(value));return Promise.resolve()};
function activateScripts(root){root.querySelectorAll('script').forEach(old=>{const next=document.createElement('script');[...old.attributes].forEach(a=>next.setAttribute(a.name,a.value));next.text=old.textContent;old.replaceWith(next)})}
const observedFrames=new WeakSet();
function fitFrame(frame){
  if(!frame||observedFrames.has(frame))return;observedFrames.add(frame);
  const setup=()=>{try{const doc=frame.contentDocument;if(!doc)return;let raf=0,first=true;const resize=()=>{raf=0;const b=doc.body,d=doc.documentElement;if(!b||!d)return;const h=Math.max(1,Math.ceil(Math.max(b.scrollHeight,b.offsetHeight,d.scrollHeight,d.offsetHeight)));frame.style.setProperty('height',h+'px','important');if(first){first=false;schedulePageHeight()}};const schedule=()=>{if(!raf)raf=requestAnimationFrame(resize)};schedule();new ResizeObserver(schedule).observe(doc.body);new MutationObserver(schedule).observe(doc.body,{subtree:true,childList:true,attributes:true});doc.querySelectorAll('img,video').forEach(x=>{x.addEventListener('load',schedule);x.addEventListener('error',schedule)});doc.addEventListener('click',()=>requestAnimationFrame(()=>{resize();schedulePageHeight()}),true);doc.addEventListener('toggle',()=>requestAnimationFrame(()=>{resize();schedulePageHeight()}),true)}catch(_){}};
  frame.addEventListener('load',setup);setup();
}
function activateFrames(root){root.querySelectorAll('iframe').forEach(fitFrame)}
new MutationObserver(records=>records.forEach(record=>{record.addedNodes.forEach(node=>{if(node.nodeType!==1)return;if(node.matches&&node.matches('iframe'))fitFrame(node);if(node.querySelectorAll)activateFrames(node)})})).observe(content,{subtree:true,childList:true});
content.addEventListener('click',()=>requestAnimationFrame(schedulePageHeight),true);content.addEventListener('toggle',()=>requestAnimationFrame(schedulePageHeight),true);
window.__fawnUpdate=function(html){const details=[...content.querySelectorAll('details')].map(x=>x.open);content.innerHTML=html;content.querySelectorAll('details').forEach((x,i)=>{if(i<details.length)x.open=details[i]});activateFrames(content);activateScripts(content);activateFrames(content);schedulePageHeight()};
</script></body></html>
""".trimIndent()

private fun Color.css(): String = String.format("#%08X", toArgb()).let { argb ->
    "#" + argb.substring(3) + argb.substring(1, 3)
}
