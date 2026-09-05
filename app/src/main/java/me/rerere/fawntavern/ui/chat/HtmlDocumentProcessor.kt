package me.rerere.fawntavern.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import org.json.JSONObject
import org.json.JSONTokener

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
    val blocks = parseFrontendFences(normalized)
    if (blocks.none { it.isHtmlResource() }) return null

    val byStart = blocks.associateBy(FrontendFence::startLine)
    val lines = normalized.split('\n')
    return buildString(normalized.length) {
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val block = byStart[lineIndex]
            if (block == null) {
                append(lines[lineIndex])
                if (lineIndex != lines.lastIndex) append('\n')
                lineIndex++
                continue
            }

            val replacement = when {
                block.language == "css" -> wrapFencedResource(block.body, "style")
                block.language == "javascript" || block.language == "js" ->
                    wrapFencedResource(block.body, "script")
                block.isHtmlResource() -> block.body
                else -> block.source
            }
            append(replacement)
            if (block.endLine != lines.lastIndex) append('\n')
            lineIndex = block.endLine + 1
        }
    }
}

internal fun encodeFrontendVariables(values: Map<String, String>): String = JSONObject().apply {
    values.forEach { (key, raw) ->
        val value = runCatching { JSONTokener(raw).nextValue() }.getOrElse { raw }
        put(key, value)
    }
}.toString()

internal fun decodeFrontendVariables(raw: String): Map<String, String>? {
    if (raw.toByteArray(Charsets.UTF_8).size > 256 * 1024) return null
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    if (json.length() > 512) return null
    return buildMap {
        json.keys().forEach { key ->
            if (key.length > 256) return@forEach
            val value = json.opt(key)
            put(key, if (value == null || value === JSONObject.NULL) "null" else value.toString())
        }
    }
}

internal fun expandFrontendRuntimeMacros(source: String, contextJson: String): String {
    val context = runCatching { JSONObject(contextJson) }.getOrElse { JSONObject() }
    val replacements = mapOf(
        "userAvatarPath" to context.optString("userAvatarPath"),
        "charAvatarPath" to context.optString("charAvatarPath"),
        "lastMessageId" to context.optInt("lastMessageId", -1).toString(),
    )
    var result = source
    replacements.forEach { (name, value) ->
        result = result.replace(Regex("(?i)\\{\\{${Regex.escape(name)}\\}\\}"), value)
    }
    return result
}

private data class FrontendFence(
    val startLine: Int,
    val endLine: Int,
    val language: String,
    val body: String,
    val source: String,
) {
    fun isHtmlResource(): Boolean {
        val hasDocumentTag = Regex("(?is)<(?:!doctype\\s+html|html|head|body)\\b").containsMatchIn(body)
        val hasHtmlElement = Regex(
            "(?is)<(?:style|script|iframe|div|section|article|details|form|main|header|footer|table|svg)\\b",
        ).containsMatchIn(body)
        return hasDocumentTag || (language in setOf("html", "htm", "frontend", "web", "xml", "vue") && hasHtmlElement) ||
            (language.isBlank() && hasHtmlElement)
    }
}

/**
 * 前端卡常把完整文档放进无类型、任意语言或波浪线围栏；围栏标签不能作为是否渲染的依据。
 * 这里只做结构提取，是否属于前端文档由块正文中的 HTML 元素决定。
 */
private fun parseFrontendFences(source: String): List<FrontendFence> {
    val lines = source.split('\n')
    val blocks = mutableListOf<FrontendFence>()
    var index = 0
    while (index < lines.size) {
        val trimmed = lines[index].trim()
        val marker = trimmed.takeWhile { it == '`' || it == '~' }
        if (marker.length < 3 || marker.any { it != marker.first() }) {
            index++
            continue
        }
        val language = trimmed.drop(marker.length).trim().substringBefore(' ').lowercase()
        var end = index + 1
        while (end < lines.size) {
            val closing = lines[end].trim()
            val closingMarker = closing.takeWhile { it == marker.first() }
            if (closingMarker.length >= marker.length && closing.drop(closingMarker.length).isBlank()) break
            end++
        }
        val closed = end < lines.size
        val endLine = if (closed) end else lines.lastIndex
        blocks += FrontendFence(
            startLine = index,
            endLine = endLine,
            language = language,
            body = lines.subList(index + 1, if (closed) end else lines.size).joinToString("\n"),
            source = lines.subList(index, endLine + 1).joinToString("\n"),
        )
        index = endLine + 1
    }
    return blocks
}

private fun wrapFencedResource(source: String, tag: String): String {
    val alreadyWrapped = Regex("(?is)^\\s*<$tag\\b").containsMatchIn(source)
    return if (alreadyWrapped) source else "<$tag>\n$source\n</$tag>"
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

internal fun fixedWebViewHeightDp(fragment: String): Int {
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
    if (allowContentJavaScript) {
        listOf(
            "vendor/fontawesome/css/all.min.css",
            "vendor/jquery-ui.min.css",
            "vendor/toastr.min.css",
        ).forEach { path ->
            document.head().appendElement("link").attr("rel", "stylesheet").attr("href", FrontendAssetOrigin + path)
        }
        document.head().appendElement("script").attr("src", FrontendAssetOrigin + "vendor/tailwindcss.min.js")
    }
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
try{const names=['_','$','jQuery','Vue','VueRouter','YAML','z','Zod','showdown','toastr','TavernHelper','SillyTavern','tavern_events','iframe_events'];names.forEach(name=>{if(window[name]===undefined&&window.parent&&window.parent[name]!==undefined)window[name]=window.parent[name]});if(window.parent&&window.parent.TavernHelper){Object.keys(window.parent.TavernHelper).forEach(name=>{if(window[name]===undefined)window[name]=window.parent.TavernHelper[name]})}}catch(_){}
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

internal fun htmlShell(
    textColor: Color,
    mutedColor: Color,
    surface: Color,
    outline: Color,
    accent: Color,
    fontSizeCssPx: Float,
    lineHeight: Float,
    allowContentJavaScript: Boolean,
): String = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1"><meta name="referrer" content="no-referrer">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: blob: https: http:; media-src data: blob: https: http:; font-src data: https: http:; style-src 'unsafe-inline' https: http:; script-src 'unsafe-inline' 'unsafe-eval' data: blob: https: http:; connect-src data: blob: https: http: wss: ws:; worker-src data: blob: https: http:; frame-src 'self' data: blob: https: http:; form-action 'none'">
${frontendDependencyHead(allowContentJavaScript)}
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
window.getChatMessages=function(range,options){let messages=[];try{messages=JSON.parse(FawnBridge.getChatMessages())}catch(_){}const opts=options&&typeof options==='object'?options:{};if(opts.role)messages=messages.filter(x=>x.role===opts.role);if(opts.hide_state==='hidden')messages=messages.filter(x=>x.is_hidden);else if(opts.hide_state==='unhidden')messages=messages.filter(x=>!x.is_hidden);if(!opts.include_swipes)messages=messages.map(x=>{const copy=Object.assign({},x);delete copy.swipes;return copy});if(range===undefined||range===null)return messages;const last=messages.length?messages[messages.length-1].message_id:-1;if(typeof range==='number'){const id=range<0?last+1+range:range;return messages.filter(x=>x.message_id===id)}const match=String(range).match(/^(-?\d+)(?:-(-?\d+))?$/);if(!match)return messages;const index=x=>{const n=Number(x);return n<0?last+1+n:n};const start=index(match[1]),end=index(match[2]??match[1]);return messages.filter(x=>x.message_id>=Math.min(start,end)&&x.message_id<=Math.max(start,end))};
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
async function renderPromptTemplate(html,incremental){
  const source=String(html??'');
  if(incremental||!window.EjsTemplate||!/(?:<|&lt;)%[_=-]?/.test(source))return source;
  try{return await window.EjsTemplate.evalTemplate(source)}catch(error){console.warn('[FawnTavern] prompt-template render failed; using the original card',error);return source}
}
window.__fawnUpdate=async function(html,incremental){
  const revision=++contentRevision,details=[...content.querySelectorAll('details')].map(x=>x.open);
  const rendered=await renderPromptTemplate(html,incremental);if(revision!==contentRevision)return;
  content.innerHTML=rendered;content.querySelectorAll('details').forEach((x,i)=>{if(i<details.length)x.open=details[i]});
  activateFrames(content);watchMedia(content);if(!incremental)await activateScripts(content);if(revision!==contentRevision)return;activateFrames(content);watchMedia(content);schedulePageHeight()
};
</script>${frontendCompatibilityScript(allowContentJavaScript)}</body></html>
""".trimIndent()

private fun frontendDependencyHead(enabled: Boolean): String = if (!enabled) "" else """
<link rel="stylesheet" href="${FrontendAssetOrigin}vendor/fontawesome/css/all.min.css">
<link rel="stylesheet" href="${FrontendAssetOrigin}vendor/jquery-ui.min.css">
<link rel="stylesheet" href="${FrontendAssetOrigin}vendor/toastr.min.css">
<script src="${FrontendAssetOrigin}vendor/tailwindcss.min.js"></script>
<script src="${FrontendAssetOrigin}vendor/lodash.min.js"></script>
<script src="${FrontendAssetOrigin}vendor/jquery.min.js"></script>
<script src="${FrontendAssetOrigin}vendor/jquery-ui.min.js"></script>
<script src="${FrontendAssetOrigin}vendor/jquery.ui.touch-punch.min.js"></script>
<script src="${FrontendAssetOrigin}vendor/vue.runtime.global.prod.js"></script>
<script src="${FrontendAssetOrigin}vendor/vue-router.global.prod.js"></script>
<script src="${FrontendAssetOrigin}vendor/js-yaml.min.js"></script>
<script src="${FrontendAssetOrigin}vendor/zod.umd.js"></script>
<script src="${FrontendAssetOrigin}vendor/showdown.min.js"></script>
<script src="${FrontendAssetOrigin}vendor/toastr.min.js"></script>
""".trimIndent()

private fun frontendCompatibilityScript(enabled: Boolean): String =
    if (enabled) "<script src=\"${FrontendAssetOrigin}tavern-helper-compat.js\"></script>" else ""

private fun Color.css(): String = String.format("#%08X", toArgb()).let { argb ->
    "#" + argb.substring(3) + argb.substring(1, 3)
}
