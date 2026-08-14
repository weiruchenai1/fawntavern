package me.rerere.fawntavern.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.mikepenz.markdown.compose.components.CurrentComponentsBridge
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
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
import coil3.compose.rememberAsyncImagePainter
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.character.RegexEngine

/** 共享的 Markdown 解析器（无状态，只在组合期的主线程上使用） */
private val markdownFlavour = GFMFlavourDescriptor()
private val markdownParser = MarkdownParser(markdownFlavour)

/**
 * 关闭文本块默认的 animateContentSize：它是给流式增长设计的，但这里内容变化（流式增长 /
 * 切分支 / 编辑）都希望**同帧**长到真实高度——让高度做 ~300ms 动画会拖慢流式跟随、并使切分支
 * 的同帧锚定漂移（句子下滑、整体下移一截）。
 */
private val noTextAnimations = DefaultMarkdownAnimation(animateTextSize = { this })

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

@Composable
internal fun ComposeMarkdownBlock(
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
        textLink = TextLinkStyles(
            style = textStyle.copy(
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
            ).toSpanStyle(),
        ),
    )
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
            val raw = kotlin.math.exp(-(dist * dist) / (2f * sigma * sigma))
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
