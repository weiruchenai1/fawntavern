package me.rerere.fawntavern.ui.chat

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.times
import androidx.core.content.ContextCompat
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.CurrentComponentsBridge
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.model.DefaultMarkdownAnimation
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import coil3.compose.rememberAsyncImagePainter
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.JLatexMathSplitter
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.character.RegexEngine
import me.rerere.fawntavern.ui.components.noRippleClickable

/** 共享的 Markdown 解析器（无状态，只在组合期的主线程上使用） */
private val markdownFlavour = GFMFlavourDescriptor()
private val markdownParser = MarkdownParser(markdownFlavour)

/**
 * 关闭文本块默认的 animateContentSize：它是给流式增长设计的，但这里内容变化（流式增长 /
 * 切分支 / 编辑）都希望**同帧**长到真实高度——让高度做 ~300ms 动画会拖慢流式跟随、并使切分支
 * 的同帧锚定漂移（句子下滑、整体下移一截）。
 */
private val noTextAnimations = DefaultMarkdownAnimation(animateTextSize = { this })

private const val MAX_INLINE_MATH_LENGTH = 512
private const val MAX_BLOCK_MATH_LENGTH = 4_096

private data class EncodedMath(val formula: String, val displayMode: Boolean)

private object MathLink {
    private const val Prefix = "latex:"

    fun encode(formula: String, displayMode: Boolean): String {
        val mode = if (displayMode) "d:" else "i:"
        return Prefix + mode + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(formula.toByteArray(Charsets.UTF_8))
    }

    fun decode(link: String): EncodedMath? {
        if (!link.startsWith(Prefix)) return null
        val encoded = link.removePrefix(Prefix)
        val displayMode = encoded.startsWith("d:")
        if (!displayMode && !encoded.startsWith("i:")) return null
        return runCatching {
            EncodedMath(
                formula = String(Base64.getUrlDecoder().decode(encoded.drop(2)), Charsets.UTF_8),
                displayMode = displayMode,
            )
        }.getOrNull()
    }
}

/** 消息渲染偏好（渲染设置分组），用户 / 思维链 / 角色消息共用一套开关 */
data class RenderPrefs(
    /** 是否以 markdown 渲染（关闭时按纯文本） */
    val markdown: Boolean = true,
    /** 识别 $…$ 与 $$…$$ 包裹的公式并单独渲染 */
    val math: Boolean = false,
    /** 超过阈值行数的代码块自动折叠 */
    val autoCollapseCode: Boolean = false,
    val codeCollapseLines: Int = 5,
)

/**
 * 聊天消息正文渲染。
 * - 流式生成中：Markdown 区段实时 Compose 渲染，裸 HTML 区段暂时显示源码。
 * - 生成结束：只有裸 HTML 区段切换为 WebView，其他区段保持 Compose 渲染。
 *
 * 同步解析（非 `Markdown(content)` 的异步重载）：内容一变即同帧成型到真实高度，流式增长
 * 平滑跟随、切分支/重试的同帧锚定不抖；`remember(processed)` 保证每段内容只解析一次，
 * 流式期间即"每 60ms 节流帧解析一次"。
 *
 * Markdown、代码、表格和公式由 Compose 渲染；AST 明确认定的裸 HTML 块才使用隔离 WebView。
 *
 * @param depth 消息深度（从底向上 0 递增），用于正则脚本的 minDepth/maxDepth 过滤
 * @param userName persona 名（用于 {{user}} 宏替换）
 * @param charName 角色名（用于 {{char}} 宏替换）
 * @param renderPrefs 渲染设置分组的开关（markdown / 数学 / 代码块折叠）
 */
@Composable
fun MessageContent(
    content: String,
    isStreaming: Boolean,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    regexScripts: List<CharRegex> = emptyList(),
    depth: Int? = null,
    userName: String = "",
    charName: String = "",
    renderPrefs: RenderPrefs = RenderPrefs(),
    /** 是否撑满可用宽度。AI 消息用 true（整行宽）；用户气泡内传 false 让气泡拥抱内容 */
    fillWidth: Boolean = true,
) {
    if (content.isBlank()) {
        // 流式等待（还没有正文，纯思考阶段）显示呼吸点；非流式空内容不占位
        if (isStreaming) StreamingDots(modifier.padding(vertical = 8.dp))
        return
    }

    // 稳定的深度键，避免新消息到达时重算旧消息
    val depthKey = remember(depth, regexScripts) {
        RegexEngine.depthKey(regexScripts, depth)
    }

    // 先套用角色卡内嵌正则，再做宏替换（流式期间同样套用，使实时预览与最终渲染一致、结尾不跳变）
    val processed = remember(content, regexScripts, depthKey, userName, charName) {
        RegexEngine.applyForDisplay(
            content = content,
            scripts = regexScripts,
            depth = depth,
            userName = userName,
            charName = charName,
        )
    }

    // markdown 关闭：按纯文本渲染（正则/宏仍套用，保证与发送侧口径一致）
    if (!renderPrefs.markdown) {
        Text(
            processed,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = if (fillWidth) modifier.fillMaxWidth() else modifier,
        )
        return
    }

    // 流式输出经常在结束围栏抵达前停留数帧。临时补齐结束围栏，代码从第一行起就按代码块
    // 测量和显示；真实结束围栏到达后补丁自然消失，不会改变最终存储内容。
    val streamSafe = remember(processed, isStreaming) {
        if (isStreaming) MessageContentParser.closeOpenCodeFence(processed) else processed
    }

    val segments = remember(streamSafe) { splitBareHtmlSegments(streamSafe) }
    if (segments.size > 1 || segments.firstOrNull()?.isHtml == true) {
        Column(modifier = if (fillWidth) modifier.fillMaxWidth() else modifier) {
            segments.forEachIndexed { index, segment ->
                key(index, segment.text.hashCode(), segment.isHtml) {
                    if (segment.isHtml) {
                        if (isStreaming) {
                            Text(segment.text, style = textStyle, color = MaterialTheme.colorScheme.onSurface)
                        } else {
                            HtmlMessageContent(
                                html = segment.text,
                                textStyle = textStyle,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        MarkdownMessageBlock(
                            content = segment.text,
                            textStyle = textStyle,
                            modifier = Modifier.fillMaxWidth(),
                            renderPrefs = renderPrefs,
                            fillWidth = true,
                        )
                    }
                }
            }
        }
        return
    }
    MarkdownMessageBlock(
        content = streamSafe,
        textStyle = textStyle,
        modifier = modifier,
        renderPrefs = renderPrefs,
        fillWidth = fillWidth,
    )
}

private data class MessageRenderSegment(val text: String, val isHtml: Boolean)

/** Split bare HTML blocks. Fenced html/css/text always remain ordinary Markdown code. */
private fun splitBareHtmlSegments(text: String): List<MessageRenderSegment> {
    val root = runCatching { markdownParser.buildMarkdownTreeFromString(text) }.getOrNull()
        ?: return listOf(MessageRenderSegment(text, false))
    fun containsHtmlTag(node: ASTNode): Boolean =
        node.type == MarkdownTokenTypes.HTML_TAG || node.children.any(::containsHtmlTag)

    val htmlBlocks = root.children.filter { node ->
        // CommonMark classifies HTML indented by four spaces as CODE_BLOCK. SillyTavern-style model
        // output often indents whole status widgets, so promote only root-level code blocks whose
        // DOM consists entirely of HTML nodes and whitespace. Fenced code has a different AST type.
        val source = text.substring(node.startOffset, node.endOffset)
        node.type == MarkdownElementTypes.HTML_BLOCK ||
            (node.type == MarkdownElementTypes.PARAGRAPH && containsHtmlTag(node)) ||
            (node.type == MarkdownElementTypes.CODE_BLOCK && isBareHtmlFragment(source))
    }
    if (htmlBlocks.isEmpty()) return listOf(MessageRenderSegment(text, false))

    val segments = mutableListOf<MessageRenderSegment>()
    fun appendSegment(value: String, html: Boolean) {
        if (value.isEmpty()) return
        val previous = segments.lastOrNull()
        if (previous != null && previous.isHtml == html) {
            segments[segments.lastIndex] = previous.copy(text = previous.text + value)
        } else {
            segments += MessageRenderSegment(value, html)
        }
    }
    var cursor = 0
    htmlBlocks.forEach { node ->
        if (node.startOffset > cursor) {
            val between = text.substring(cursor, node.startOffset)
            if (between.isBlank() && segments.lastOrNull()?.isHtml == true) {
                appendSegment(between, true)
            } else {
                appendSegment(between, false)
            }
        }
        appendSegment(text.substring(node.startOffset, node.endOffset), true)
        cursor = node.endOffset
    }
    if (cursor < text.length) {
        val tail = text.substring(cursor)
        if (tail.isBlank() && segments.lastOrNull()?.isHtml == true) appendSegment(tail, true)
        else appendSegment(tail, false)
    }
    return segments.filter { it.text.isNotBlank() }
}

@Composable
private fun MarkdownMessageBlock(
    content: String,
    textStyle: TextStyle,
    modifier: Modifier,
    renderPrefs: RenderPrefs,
    fillWidth: Boolean,
) {
    ComposeMarkdownBlock(content, textStyle, modifier, renderPrefs, fillWidth)
}

private data class MathRenderSegment(
    val text: String,
    val formula: Boolean,
    val displayMode: Boolean = false,
)

private data class MathDelimiterMatch(
    val start: Int,
    val endExclusive: Int,
    val formula: String,
    val displayMode: Boolean,
)

private fun splitMathSegments(text: String, includeInline: Boolean = true): List<MathRenderSegment> {
    val root = runCatching { markdownParser.buildMarkdownTreeFromString(text) }.getOrNull() ?: return emptyList()
    val protected = mutableListOf<IntRange>()
    fun collectCode(node: ASTNode) {
        if (node.type == MarkdownElementTypes.CODE_FENCE || node.type == MarkdownElementTypes.CODE_BLOCK ||
            node.type == MarkdownElementTypes.CODE_SPAN || node.type == GFMElementTypes.TABLE) {
            protected += node.startOffset until node.endOffset
            return
        }
        node.children.forEach(::collectCode)
    }
    collectCode(root)
    val matches = findMathDelimiters(text, includeInline).filter { match ->
        protected.none { range -> match.start <= range.last && match.endExclusive - 1 >= range.first }
    }
    if (matches.isEmpty()) return emptyList()

    val result = mutableListOf<MathRenderSegment>()
    var cursor = 0
    matches.forEach { match ->
        if (match.start > cursor) result += MathRenderSegment(text.substring(cursor, match.start), false)
        result += MathRenderSegment(
            text = match.formula.trim(),
            formula = true,
            displayMode = match.displayMode,
        )
        cursor = match.endExclusive
    }
    if (cursor < text.length) result += MathRenderSegment(text.substring(cursor), false)
    return result.filter { it.text.isNotBlank() }
}

private fun findMathDelimiters(text: String, includeInline: Boolean): List<MathDelimiterMatch> {
    val result = mutableListOf<MathDelimiterMatch>()
    var index = 0
    while (index < text.length) {
        val match = when {
            text.startsWith("$$", index) && !isEscapedAt(text, index) ->
                findDelimitedMath(text, index, "$$", "$$", MAX_BLOCK_MATH_LENGTH, true)
            text.startsWith("\\[", index) && !isEscapedAt(text, index) ->
                findDelimitedMath(text, index, "\\[", "\\]", MAX_BLOCK_MATH_LENGTH, true)
            includeInline && text.startsWith("\\(", index) && !isEscapedAt(text, index) ->
                findDelimitedMath(text, index, "\\(", "\\)", MAX_INLINE_MATH_LENGTH, false, allowNewline = false)
            includeInline && text[index] == '$' &&
                (index == 0 || text[index - 1] != '$') &&
                (index + 1 >= text.length || text[index + 1] != '$') &&
                canOpenInlineDollar(text, index) -> findInlineDollarMath(text, index)
            else -> null
        }
        if (match != null) {
            result += match
            index = match.endExclusive
        } else {
            index++
        }
    }
    return result
}

private fun findDelimitedMath(
    text: String,
    start: Int,
    open: String,
    close: String,
    maxLength: Int,
    displayMode: Boolean,
    allowNewline: Boolean = true,
): MathDelimiterMatch? {
    val bodyStart = start + open.length
    val searchEnd = minOf(text.length, bodyStart + maxLength + close.length)
    var cursor = bodyStart
    while (cursor < searchEnd) {
        if (!allowNewline && text[cursor] == '\n') return null
        if (text.startsWith(close, cursor) && !isEscapedAt(text, cursor)) {
            val body = text.substring(bodyStart, cursor).trim()
            if (body.isEmpty()) return null
            return MathDelimiterMatch(start, cursor + close.length, body, displayMode)
        }
        cursor++
    }
    return null
}

private fun findInlineDollarMath(text: String, start: Int): MathDelimiterMatch? {
    val bodyStart = start + 1
    val searchEnd = minOf(text.length, bodyStart + MAX_INLINE_MATH_LENGTH + 1)
    var cursor = bodyStart
    while (cursor < searchEnd) {
        val char = text[cursor]
        if (char == '\n') return null
        if (char == '$' && !isEscapedAt(text, cursor)) {
            if (cursor + 1 < text.length && text[cursor + 1] == '$') return null
            val body = text.substring(bodyStart, cursor)
            if (body.isNotEmpty() && !body.first().isWhitespace() && !body.last().isWhitespace() &&
                canCloseInlineDollar(text, cursor)) {
                return MathDelimiterMatch(start, cursor + 1, body, false)
            }
            return null
        }
        cursor++
    }
    return null
}

private fun canOpenInlineDollar(text: String, index: Int): Boolean {
    if (index + 1 >= text.length || text[index + 1].isWhitespace()) return false
    if (index == 0) return true
    val previous = text[index - 1]
    return previous.isWhitespace() || isCjk(previous) || previous in "([{（【=:;,!?，。！？、：；"
}

private fun canCloseInlineDollar(text: String, index: Int): Boolean {
    if (index == 0 || text[index - 1].isWhitespace()) return false
    if (index + 1 >= text.length) return true
    val next = text[index + 1]
    return next.isWhitespace() || isCjk(next) || next in ")]}）】,.:;!?，。！？、：；"
}

private fun isCjk(char: Char): Boolean = char.code in 0x3400..0x4DBF ||
    char.code in 0x4E00..0x9FFF || char.code in 0xF900..0xFAFF

private fun isEscapedAt(text: String, index: Int): Boolean {
    var slashes = 0
    var cursor = index - 1
    while (cursor >= 0 && text[cursor] == '\\') {
        slashes++
        cursor--
    }
    return slashes % 2 == 1
}

private fun prepareMath(text: String): String {
    val root = runCatching { markdownParser.buildMarkdownTreeFromString(text) }.getOrNull() ?: return text
    val protected = mutableListOf<IntRange>()
    fun collectProtected(node: ASTNode) {
        if (node.type == MarkdownElementTypes.CODE_FENCE || node.type == MarkdownElementTypes.CODE_BLOCK ||
            node.type == MarkdownElementTypes.CODE_SPAN || node.type == GFMElementTypes.TABLE) {
            protected += node.startOffset until node.endOffset
            return
        }
        node.children.forEach(::collectProtected)
    }
    collectProtected(root)
    val matches = findMathDelimiters(text, includeInline = true).filter { match ->
        protected.none { range ->
            match.start <= range.last && match.endExclusive - 1 >= range.first
        }
    }
    if (matches.isEmpty()) return text
    return buildString(text.length + matches.size * 12) {
        var cursor = 0
        matches.forEach { match ->
            append(text, cursor, match.start)
            append("![latex](")
            append(MathLink.encode(match.formula, match.displayMode))
            append(')')
            cursor = match.endExclusive
        }
        append(text, cursor, text.length)
    }
}

@Composable
private fun ComposeMarkdownBlock(
    content: String,
    textStyle: TextStyle,
    modifier: Modifier,
    renderPrefs: RenderPrefs,
    fillWidth: Boolean,
) {
    var previewImage by remember { mutableStateOf<String?>(null) }
    val prepared = remember(content, renderPrefs.math) {
        if (renderPrefs.math) prepareMath(content) else content
    }
    // 此处只接收非 HTML 区段，全部走 mikepenz Markdown。它负责 ``` 代码围栏、**粗体**、
    // *斜体*、链接和 GFM 表格；裸 HTML 已在上层按 AST 边界分离。
    //
    // 同步解析：Markdown(content) 重载默认异步解析，消息会先以极小高度上屏、几帧后才长到真实高度，
    // 流式增长与跳转/切分支/重试后的重新定位会因此肉眼可见地抖动。此处同步解析一次成型，
    // remember(processed) 保证每段内容只解析一次（流式期间即每帧节流刷新时解析一次）。
    val markdownState = remember(prepared) {
        val md = MessageContentParser.prepareMarkdown(prepared)
        val handler = ReferenceLinkHandlerImpl()
        try {
            // linksLookedUp = false：链接定义节点由渲染器在组合期自行登记到 handler
            State.Success(markdownParser.buildMarkdownTreeFromString(md), md, false, handler)
        } catch (e: Throwable) {
            State.Error(e, handler)
        }
    }
    // Markdown、代码围栏和表格始终留在 Compose；只有顶层分段识别出的裸 HTML 使用 WebView。
    val components = remember(
        renderPrefs.math,
        renderPrefs.autoCollapseCode,
        renderPrefs.codeCollapseLines,
    ) {
        markdownComponents(
            checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
            paragraph = if (renderPrefs.math) { model ->
                LatexFlowMarkdownText(model, model.typography.paragraph)
            } else CurrentComponentsBridge.paragraph,
            heading1 = latexFlowHeading(renderPrefs.math) { it.typography.h1 },
            heading2 = latexFlowHeading(renderPrefs.math) { it.typography.h2 },
            heading3 = latexFlowHeading(renderPrefs.math) { it.typography.h3 },
            heading4 = latexFlowHeading(renderPrefs.math) { it.typography.h4 },
            heading5 = latexFlowHeading(renderPrefs.math) { it.typography.h5 },
            heading6 = latexFlowHeading(renderPrefs.math) { it.typography.h6 },
            setextHeading1 = latexFlowHeading(
                renderPrefs.math,
                MarkdownTokenTypes.SETEXT_CONTENT,
            ) { it.typography.h1 },
            setextHeading2 = latexFlowHeading(
                renderPrefs.math,
                MarkdownTokenTypes.SETEXT_CONTENT,
            ) { it.typography.h2 },
            table = { model ->
                ChatMarkdownTable(
                    content = model.content,
                    node = model.node,
                    style = model.typography.table,
                    renderMath = renderPrefs.math,
                )
            },
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, style ->
                    if (renderPrefs.math && language.equals("math", ignoreCase = true)) {
                        LatexFormulaBlock(code.trim(), style)
                    } else {
                        ChatCodeBlock(
                            code = code,
                            language = language,
                            style = style,
                            collapsible = renderPrefs.autoCollapseCode,
                            threshold = renderPrefs.codeCollapseLines,
                        )
                    }
                }
            },
        )
    }
    val imageTransformer = remember {
        PreviewImageTransformer { previewImage = it }
    }
    // 每个 markdown 元素前都会加 Spacer(block 高度)，AI 消息靠它撑开段落间距；
    // 用户气泡里首元素前的 6dp Spacer 会让文字整体下移、气泡顶部空一截，故气泡内 block 用 0
    Markdown(
        state = markdownState,
        typography = chatMarkdownTypography(textStyle),
        padding = markdownPadding(block = if (fillWidth) 6.dp else 0.dp),
        modifier = if (fillWidth) modifier.fillMaxWidth() else modifier,
        animations = noTextAnimations,
        components = components,
        imageTransformer = imageTransformer,
    )
    previewImage?.let { image ->
        ImagePreviewDialog(model = image, onDismiss = { previewImage = null })
    }
}

private class PreviewImageTransformer(
    private val onClick: (String) -> Unit,
) : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? {
        if (link.startsWith("latex:")) return null
        return ImageData(
            painter = rememberAsyncImagePainter(model = link, contentScale = ContentScale.Fit),
            modifier = Modifier.fillMaxWidth().clickable { onClick(link) },
            contentDescription = null,
            alignment = Alignment.Center,
            contentScale = ContentScale.Fit,
        )
    }
}

/** A fixed toolbar plus a horizontally scrollable, image-exportable table body. */
@Composable
private fun ChatMarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle,
    renderMath: Boolean,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val graphicsLayer = rememberGraphicsLayer()
    var isSaving by remember { mutableStateOf(false) }
    val columns = remember(node) {
        node.findChildOfType(GFMElementTypes.HEADER)?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0
    }
    val tableWidth = columns.coerceAtLeast(1) * 148.dp
    val tableSource = remember(content, node) { content.substring(node.startOffset, node.endOffset) }
    val saveImage: () -> Unit = {
        if (!isSaving) scope.launch {
            isSaving = true
            val saved = runCatching {
                val image = graphicsLayer.toImageBitmap().asAndroidBitmap()
                withContext(Dispatchers.IO) { saveBitmapToGallery(context, image) }
            }.getOrDefault(false)
            isSaving = false
            Toast.makeText(
                context,
                context.getString(if (saved) R.string.image_saved_to_gallery else R.string.image_save_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveImage()
        else Toast.makeText(context, R.string.image_save_failed, Toast.LENGTH_SHORT).show()
    }
    val outline = MaterialTheme.colorScheme.outlineVariant
    val tableBackground = MaterialTheme.colorScheme.surfaceContainerLow
    val panelShape = RoundedCornerShape(6.dp)

    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(panelShape)
            .background(tableBackground),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.markdown_table),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Icon(
                Lucide.Copy,
                stringResource(R.string.copy),
                Modifier.size(36.dp).noRippleClickable {
                    clipboard.setText(AnnotatedString(tableSource))
                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                }.padding(9.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Lucide.Download,
                stringResource(R.string.download),
                Modifier.size(36.dp).noRippleClickable {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED) {
                        saveImage()
                    } else {
                        permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }.padding(9.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = outline)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val scrollable = tableWidth > maxWidth
            Column(
                (if (scrollable) {
                    Modifier.horizontalScroll(rememberScrollState()).requiredWidth(tableWidth)
                } else {
                    Modifier.fillMaxWidth()
                }).drawWithContent {
                    graphicsLayer.record {
                        drawRect(tableBackground)
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(graphicsLayer)
                },
            ) {
                var renderedRows = 0
                node.children.forEach { child ->
                    val isHeader = child.type == GFMElementTypes.HEADER
                    if (isHeader || child.type == GFMElementTypes.ROW) {
                        if (renderedRows > 0) HorizontalDivider(color = outline)
                        ChatMarkdownTableRow(
                            content = content,
                            row = child,
                            tableWidth = tableWidth,
                            style = style,
                            header = isHeader,
                            renderMath = renderMath,
                            dividerColor = outline,
                        )
                        renderedRows++
                    }
                }
            }
        }
    }
}

/** Keep the library's table geometry, but render TeX delimiters inside individual cells. */
@Composable
private fun ChatMarkdownTableRow(
    content: String,
    row: ASTNode,
    tableWidth: Dp,
    style: TextStyle,
    header: Boolean,
    renderMath: Boolean,
    dividerColor: androidx.compose.ui.graphics.Color,
) {
    val markdownComponents = LocalMarkdownComponents.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(tableWidth).height(IntrinsicSize.Max),
    ) {
        row.children.filter { it.type == GFMTokenTypes.CELL }.forEachIndexed { index, cell ->
            if (index > 0) VerticalDivider(color = dividerColor)
            val cellSource = remember(content, cell) {
                content.substring(cell.startOffset, cell.endOffset).trim()
            }
            val segments = remember(cellSource, renderMath) {
                if (renderMath) splitMathSegments(cellSource) else emptyList()
            }
            val cellStyle = if (header) style.copy(fontWeight = FontWeight.Bold) else style
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).weight(1f)) {
                if (segments.isEmpty() && cell.children.any { it.type == MarkdownElementTypes.IMAGE }) {
                    MarkdownElement(
                        node = cell,
                        components = markdownComponents,
                        content = content,
                        includeSpacer = false,
                    )
                } else if (segments.isEmpty()) {
                    MarkdownTableBasicText(
                        content = content,
                        cell = cell,
                        style = cellStyle,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Clip,
                    )
                } else {
                    segments.forEachIndexed { index, segment ->
                        key(index, segment.text.hashCode(), segment.formula, segment.displayMode) {
                            if (segment.formula) {
                                LatexFormulaBlock(
                                    formula = segment.text,
                                    style = cellStyle,
                                    displayMode = segment.displayMode,
                                    compact = true,
                                )
                            } else {
                                ComposeMarkdownBlock(
                                    content = segment.text,
                                    textStyle = cellStyle,
                                    modifier = Modifier.fillMaxWidth(),
                                    renderPrefs = RenderPrefs(math = false),
                                    fillWidth = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean {
    val fileName = "FawnTavern-table-${System.currentTimeMillis()}.png"
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FawnTavern")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        try {
            val written = resolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            } == true
            if (!written) {
                resolver.delete(uri, null, null)
                false
            } else {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            }
        } catch (_: Throwable) {
            resolver.delete(uri, null, null)
            false
        }
    } else {
        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "FawnTavern",
        )
        if (!directory.exists() && !directory.mkdirs()) return false
        val file = File(directory, fileName)
        val written = runCatching {
            FileOutputStream(file).use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        }.getOrDefault(false)
        if (written) MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
        written
    }
}

/**
 * 聊天场景的 Markdown 排版：全部样式派生自消息正文的 [textStyle]（已含全局字体缩放），
 * 修掉两个库默认值的问题——
 * 1. m3 版默认把 h1–h3 映射到 Display 级别（57/45/36sp），聊天气泡里过于夸张，
 *    改为正文的 1.5/1.3/1.15 倍加粗（接近浏览器标题比例再压一档）；
 * 2. 默认正文固定用 MaterialTheme 的 bodyLarge，不吃 FontSizeStore 的字体缩放。
 * 标题行高按缩放后的字号重算，避免大字号被原行高裁切。
 */
@Composable
private fun chatMarkdownTypography(textStyle: TextStyle): MarkdownTypography {
    fun heading(factor: Float): TextStyle {
        val size = textStyle.fontSize * factor
        return textStyle.copy(fontSize = size, lineHeight = size * 1.35f, fontWeight = FontWeight.Bold)
    }
    val code = textStyle.copy(
        fontSize = textStyle.fontSize * 0.9f,
        fontFamily = FontFamily.Monospace,
    )
    return markdownTypography(
        h1 = heading(1.5f),
        h2 = heading(1.3f),
        h3 = heading(1.15f),
        h4 = heading(1.05f),
        h5 = heading(1f),
        h6 = heading(1f),
        text = textStyle,
        paragraph = textStyle,
        ordered = textStyle,
        bullet = textStyle,
        list = textStyle,
        table = textStyle,
        code = code,
        inlineCode = code,
        quote = textStyle.copy(fontStyle = FontStyle.Italic),
        link = textStyle.copy(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline),
    )
}

private fun latexFlowHeading(
    enabled: Boolean,
    contentChildType: IElementType = MarkdownTokenTypes.ATX_CONTENT,
    style: (MarkdownComponentModel) -> TextStyle,
): MarkdownComponent = if (enabled) {
    { model ->
        LatexFlowMarkdownText(
            model = model,
            style = style(model),
            contentChildType = contentChildType,
        )
    }
} else {
    { model -> MarkdownHeader(model.content, model.node, style(model), contentChildType) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LatexFlowMarkdownText(
    model: MarkdownComponentModel,
    style: TextStyle,
    contentChildType: IElementType? = null,
) {
    val child = contentChildType?.let { type -> model.node.children.firstOrNull { it.type == type } } ?: model.node
    val settings = annotatorSettings()
    val styled = remember(model.content, child, style, settings) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(model.content, child, settings)
            pop()
        }
    }
    val formulas = remember(styled) {
        styled.getStringAnnotations(start = 0, end = styled.length).mapNotNull { range ->
            if (range.item != MARKDOWN_TAG_IMAGE_URL) return@mapNotNull null
            val link = styled.text.substring(range.start, range.end)
            MathLink.decode(link)?.let { encoded -> Triple(range, encoded, link) }
        }
    }
    if (formulas.isEmpty()) {
        MarkdownText(styled, style = style)
        return
    }

    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val fontSizePx = with(density) {
        if (style.fontSize.isSpecified) style.fontSize.toPx() else 16.dp.toPx()
    }
    val rendered = formulas.map { (range, encoded, _) ->
        val drawables = remember(encoded, fontSizePx, color) {
            if (encoded.displayMode || encoded.formula.length > MAX_INLINE_MATH_LENGTH) {
                emptyList()
            } else runCatching {
                JLatexMathSplitter.split(encoded.formula, fontSizePx * 8f, fontSizePx, color)
            }.getOrElse { emptyList() }
        }
        FlowFormula(range, encoded.formula, encoded.displayMode, drawables)
    }

    FlowRow(modifier = Modifier.fillMaxWidth()) {
        var cursor = 0
        rendered.forEach { item ->
            if (item.range.start > cursor) {
                MarkdownText(
                    styled.subSequence(cursor, item.range.start),
                    modifier = Modifier.alignByBaseline(),
                    style = style,
                )
            }
            if (item.displayMode) {
                LatexFormulaBlock(item.formula, style, displayMode = true)
            } else if (item.drawables.isEmpty()) {
                Text(
                    "\$${item.formula}\$",
                    modifier = Modifier.alignByBaseline(),
                    style = style,
                )
            } else {
                item.drawables.forEach { drawable ->
                    LatexDrawableCanvas(
                        drawable = drawable,
                        modifier = Modifier.alignBy { measured ->
                            // TeX drawables expose no baseline; center them around the text baseline
                            // while reserving roughly one quarter-em for the normal descender area.
                            (measured.measuredHeight / 2f + fontSizePx * 0.25f)
                                .toInt()
                                .coerceIn(0, measured.measuredHeight)
                        },
                    )
                }
            }
            cursor = item.range.end
        }
        if (cursor < styled.length) {
            MarkdownText(
                styled.subSequence(cursor, styled.length),
                modifier = Modifier.alignByBaseline(),
                style = style,
            )
        }
    }
}

private data class FlowFormula(
    val range: AnnotatedString.Range<String>,
    val formula: String,
    val displayMode: Boolean,
    val drawables: List<JLatexMathDrawable>,
)

@Composable
private fun LatexDrawableCanvas(
    drawable: JLatexMathDrawable,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val width = with(density) { drawable.bounds.width().coerceIn(1, 8_192).toDp() }
    val height = with(density) { drawable.bounds.height().coerceIn(1, 2_048).toDp() }
    Canvas(modifier.size(width, height)) {
        runCatching {
            drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            drawable.draw(drawContext.canvas.nativeCanvas)
        }
    }
}

@Composable
private fun LatexFormulaBlock(
    formula: String,
    style: TextStyle,
    displayMode: Boolean = true,
    compact: Boolean = false,
) {
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val textSizePx = with(density) {
        if (style.fontSize.isSpecified) style.fontSize.toPx() else 16.dp.toPx()
    }
    val drawable = remember(formula, textSizePx, color, displayMode, compact) {
        if (formula.length > MAX_BLOCK_MATH_LENGTH) null else runCatching {
            JLatexMathDrawable.builder(formula)
                .textSize(textSizePx)
                .color(color)
                .padding(if (displayMode && !compact) 2 else 0)
                .align(JLatexMathDrawable.ALIGN_LEFT)
                .build()
        }.getOrNull()
    }
    if (drawable == null) {
        Text(formula, style = style)
        return
    }
    // Keep normal equations at their intrinsic width so the viewport scrolls horizontally. The
    // upper bounds prevent hostile or malformed model output from overflowing Compose constraints.
    val width = with(density) { drawable.intrinsicWidth.coerceIn(1, 16_384).toDp() }
    val height = with(density) { drawable.intrinsicHeight.coerceIn(1, 4_096).toDp() }
    Box(
        if (compact) {
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 2.dp)
        } else {
            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 2.dp, vertical = 2.dp)
        },
    ) {
        Canvas(Modifier.size(width, height)) {
            runCatching {
                drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                drawable.draw(drawContext.canvas.nativeCanvas)
            }
        }
    }
}

/** Chat-oriented code surface with a stable metadata/action bar and optional line-limit folding. */
@Composable
private fun ChatCodeBlock(
    code: String,
    language: String?,
    style: TextStyle,
    collapsible: Boolean,
    threshold: Int,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lineCount = code.count { it == '\n' } + 1
    val canFold = lineCount > threshold
    // null 表示遵循自动折叠策略；用户点过后保存明确选择。不能把 code 放进 remember key，
    // 否则流式每个 token 都会把刚刚展开的代码块重新折叠。
    var expansionOverride by remember(collapsible, threshold) { mutableStateOf<Boolean?>(null) }
    val expanded = expansionOverride ?: !(collapsible && canFold)
    var copied by remember(code) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val colors = LocalMarkdownColors.current
    val codePad = LocalMarkdownPadding.current.codeBlock
    val labelStyle = MaterialTheme.typography.labelSmall
    val normalizedLanguage = language?.trim()?.lowercase().orEmpty()
    val fileType = remember(normalizedLanguage) { codeFileType(normalizedLanguage) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(fileType.mimeType),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                        writer.write(code)
                        true
                    } == true
                }.getOrDefault(false)
            }
            Toast.makeText(
                context,
                context.getString(if (saved) R.string.file_saved else R.string.file_save_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                normalizedLanguage.takeIf { it.isNotEmpty() }?.uppercase()
                    ?: stringResource(R.string.code_language_plain),
                style = labelStyle.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Icon(
                if (copied) Lucide.Check else Lucide.Copy,
                stringResource(R.string.copy),
                Modifier.size(36.dp).noRippleClickable {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                }.padding(9.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Lucide.Download,
                stringResource(R.string.download),
                Modifier.size(36.dp).noRippleClickable {
                    exportLauncher.launch(
                        "FawnTavern-code-${System.currentTimeMillis()}.${fileType.extension}",
                    )
                }.padding(9.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canFold) {
                Icon(
                    if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    stringResource(if (expanded) R.string.collapse else R.string.expand),
                    Modifier.size(36.dp).noRippleClickable { expansionOverride = !expanded }.padding(9.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val visibleCode = remember(code, expanded, threshold) {
            if (expanded) code else code.lineSequence().take(threshold).joinToString("\n")
        }
        Box(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                visibleCode,
                style = style,
                color = colors.codeText,
                softWrap = false,
                modifier = Modifier.padding(codePad),
            )
        }
    }
}

private data class CodeFileType(val extension: String, val mimeType: String)

private fun codeFileType(language: String): CodeFileType = when (language) {
    "html", "htm" -> CodeFileType("html", "text/html")
    "css" -> CodeFileType("css", "text/css")
    "javascript", "js" -> CodeFileType("js", "text/javascript")
    "typescript", "ts" -> CodeFileType("ts", "text/plain")
    "json" -> CodeFileType("json", "application/json")
    "xml" -> CodeFileType("xml", "application/xml")
    "markdown", "md" -> CodeFileType("md", "text/markdown")
    "kotlin", "kt" -> CodeFileType("kt", "text/plain")
    "java" -> CodeFileType("java", "text/x-java")
    "python", "py" -> CodeFileType("py", "text/x-python")
    "shell", "bash", "sh" -> CodeFileType("sh", "text/x-shellscript")
    "sql" -> CodeFileType("sql", "application/sql")
    else -> CodeFileType("txt", "text/plain")
}

/** 三个依次呼吸亮起的小圆点，用于流式生成等待状态。 */
@Composable
fun StreamingDots(
    modifier: Modifier = Modifier,
    dotSize: Dp = 6.dp,
    spacing: Dp = 5.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "streamingDots")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dotTime",
    )

    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    val sigma = 0.65f  // 光斑宽度：越小越集中，越大相邻点重叠越多

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until 3) {
            // 光斑中心从左到右滑动 0→3，用模运算做环形距离让光斑从点 2 平滑回到点 0
            val center = time * 3f
            val dist = kotlin.math.abs((i - center + 4.5f) % 3f - 1.5f)
            // 高斯核：exp(-d² / 2σ²)，光斑中心最亮，向两侧平滑衰减
            val raw = kotlin.math.exp(-(dist * dist) / (2f * sigma * sigma)).toFloat()
            val alpha = 0.12f + raw * 0.83f

            Box(
                Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = alpha)),
            )
        }
    }
}
