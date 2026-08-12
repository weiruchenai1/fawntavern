package me.rerere.fawntavern.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
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
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.components.CurrentComponentsBridge
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.model.DefaultMarkdownAnimation
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.FileCode2
import com.composables.icons.lucide.Lucide
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import ru.noties.jlatexmath.JLatexMathDrawable
import java.util.Base64
import kotlinx.coroutines.delay
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

/** 把 TeX 片段转换成 Markdown 可定位的节点；节点最终由 JLaTeXMath 绘制。 */
private object MathFormula {
    private val block = Regex("""\$\$([\s\S]+?)\$\$""")
    // 行内 $…$：两侧内容以非空白收尾、紧贴 $（避免误伤 $5 这类货币），不跨行
    private val inline = Regex("""(?<!\$)\$(?!\$)(?=\S)([^\$\n]+?)(?<=\S)\$(?!\$)""")
    private const val LinkPrefix = "latex:"

    fun prepare(text: String): String {
        val afterBlock = block.replace(text) { "```math\n${it.groupValues[1].trim()}\n```" }
        return inline.replace(afterBlock) {
            val encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(it.groupValues[1].toByteArray(Charsets.UTF_8))
            "![latex]($LinkPrefix$encoded)"
        }
    }

    fun decodeLink(link: String): String? {
        if (!link.startsWith(LinkPrefix)) return null
        return try {
            String(Base64.getUrlDecoder().decode(link.removePrefix(LinkPrefix)), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * 聊天消息正文渲染。
 * - 流式生成中：**实时** Markdown 渲染（与结束态同一条管线：先套正则+宏，再 Markdown），
 *   随每帧节流刷新的内容增量重解析上屏——不再是纯文本、结束才一次性渲染。正文尚为空
 *   （纯思考阶段）时显示呼吸点。
 * - 生成结束：同样的管线渲染最终内容。
 *
 * 同步解析（非 `Markdown(content)` 的异步重载）：内容一变即同帧成型到真实高度，流式增长
 * 平滑跟随、切分支/重试的同帧锚定不抖；`remember(processed)` 保证每段内容只解析一次，
 * 流式期间即"每 60ms 节流帧解析一次"。
 *
 * 注意：不使用 WebView，避免 WebView 与 LazyColumn 嵌套导致的布局闪烁/滚动跳页。
 * 所有内容（包括裸 HTML 标签和 ```html 围栏）均通过 mikepenz 渲染。
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

    // 数学公式开启时先把 $$…$$ / $…$ 转成可渲染形态，再交给 markdown 解析
    val prepared = remember(processed, renderPrefs.math) {
        if (renderPrefs.math) MathFormula.prepare(processed) else processed
    }
    // 全部走 mikepenz Markdown。mikepenz 原生支持 ``` 代码围栏、**粗体**、*斜体*、链接等 GFM 语法。
    // HTML 标签（如 <b>/<i>/<StatusBlock>）如果被 mikepenz 识别为 HTML 则会渲染，否则显示源码。
    //
    // 同步解析：Markdown(content) 重载默认异步解析，消息会先以极小高度上屏、几帧后才长到真实高度，
    // 流式增长与跳转/切分支/重试后的重新定位会因此肉眼可见地抖动。此处同步解析一次成型，
    // remember(processed) 保证每段内容只解析一次（流式期间即每帧节流刷新时解析一次）。
    val markdownState = remember(prepared) {
        val md = prepareMarkdown(prepared)
        val handler = ReferenceLinkHandlerImpl()
        try {
            // linksLookedUp = false：链接定义节点由渲染器在组合期自行登记到 handler
            State.Success(markdownParser.buildMarkdownTreeFromString(md), md, false, handler)
        } catch (e: Throwable) {
            State.Error(e, handler)
        }
    }
    // 代码围栏统一走聊天专用工具栏；math 围栏交给本地 TeX 引擎。行内公式则在段落组件中
    // 换成拥有独立尺寸的 InlineTextContent，保留正文中的自然基线与换行。
    val components = remember(renderPrefs.math, renderPrefs.autoCollapseCode, renderPrefs.codeCollapseLines) {
        markdownComponents(
            checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
            paragraph = if (renderPrefs.math) { model ->
                LatexMarkdownText(model, model.typography.paragraph)
            } else CurrentComponentsBridge.paragraph,
            heading1 = latexHeading(renderPrefs.math) { it.typography.h1 },
            heading2 = latexHeading(renderPrefs.math) { it.typography.h2 },
            heading3 = latexHeading(renderPrefs.math) { it.typography.h3 },
            heading4 = latexHeading(renderPrefs.math) { it.typography.h4 },
            heading5 = latexHeading(renderPrefs.math) { it.typography.h5 },
            heading6 = latexHeading(renderPrefs.math) { it.typography.h6 },
            setextHeading1 = latexHeading(renderPrefs.math, MarkdownTokenTypes.SETEXT_CONTENT) { it.typography.h1 },
            setextHeading2 = latexHeading(renderPrefs.math, MarkdownTokenTypes.SETEXT_CONTENT) { it.typography.h2 },
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
    // 每个 markdown 元素前都会加 Spacer(block 高度)，AI 消息靠它撑开段落间距；
    // 用户气泡里首元素前的 6dp Spacer 会让文字整体下移、气泡顶部空一截，故气泡内 block 用 0
    Markdown(
        state = markdownState,
        typography = chatMarkdownTypography(textStyle),
        padding = markdownPadding(block = if (fillWidth) 6.dp else 0.dp),
        modifier = if (fillWidth) modifier.fillMaxWidth() else modifier,
        animations = noTextAnimations,
        components = components,
    )
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

private fun latexHeading(
    enabled: Boolean,
    contentChildType: IElementType = MarkdownTokenTypes.ATX_CONTENT,
    style: (MarkdownComponentModel) -> TextStyle,
): MarkdownComponent = if (enabled) {
    { model: MarkdownComponentModel ->
        LatexMarkdownText(
            model = model,
            style = style(model),
            contentChildType = contentChildType,
            heading = true,
        )
    }
} else {
    { model: MarkdownComponentModel ->
        MarkdownHeader(model.content, model.node, style(model), contentChildType)
    }
}

/** Renders the Markdown paragraph normally, replacing only latex: image placeholders with sized TeX canvases. */
@Composable
private fun LatexMarkdownText(
    model: MarkdownComponentModel,
    style: TextStyle,
    contentChildType: IElementType? = null,
    heading: Boolean = false,
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
        styled.getStringAnnotations(start = 0, end = styled.length)
            .mapNotNull { range ->
                if (range.item != MARKDOWN_TAG_IMAGE_URL) return@mapNotNull null
                val link = styled.text.substring(range.start, range.end)
                MathFormula.decodeLink(link)?.let { formula -> Triple(range, formula, link) }
            }
    }
    if (formulas.isEmpty()) {
        MarkdownText(styled, modifier = if (heading) Modifier.semantics { heading() } else Modifier, style = style)
        return
    }

    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val textSizePx = with(density) {
        if (style.fontSize.isSpecified) style.fontSize.toPx() else 16.dp.toPx()
    }
    val rendered = formulas.mapIndexed { index, (range, formula, _) ->
        val drawable = remember(formula, textSizePx, color) {
            runCatching {
                JLatexMathDrawable.builder("\\textstyle $formula")
                    .textSize(textSizePx)
                    .color(color)
                    .padding(1)
                    .fitCanvas(true)
                    .build()
            }.getOrNull()
        }
        InlineFormula(index, range, formula, drawable)
    }
    val transformed = remember(styled, rendered.map { it.formula to (it.drawable != null) }) {
        buildAnnotatedString {
            var cursor = 0
            rendered.forEach { item ->
                append(styled.subSequence(cursor, item.range.start))
                if (item.drawable == null) {
                    append("\$${item.formula}\$")
                } else {
                    appendInlineContent(item.id, "\uFFFC")
                }
                cursor = item.range.end
            }
            append(styled.subSequence(cursor, styled.length))
        }
    }
    val inlineContent = rendered.mapNotNull { item ->
        val drawable = item.drawable ?: return@mapNotNull null
        val width = with(density) { drawable.intrinsicWidth.coerceAtLeast(1).toSp() }
        val height = with(density) { drawable.intrinsicHeight.coerceAtLeast(1).toSp() }
        item.id to InlineTextContent(
            Placeholder(width, height, PlaceholderVerticalAlign.TextCenter)
        ) {
            Canvas(Modifier.fillMaxWidth()) {
                drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                drawable.draw(drawContext.canvas.nativeCanvas)
            }
        }
    }.toMap()
    CompositionLocalProvider(LocalMarkdownInlineContent provides markdownInlineContent(inlineContent)) {
        MarkdownText(
            transformed,
            modifier = if (heading) Modifier.semantics { heading() } else Modifier,
            style = style,
        )
    }
}

private data class InlineFormula(
    val index: Int,
    val range: AnnotatedString.Range<String>,
    val formula: String,
    val drawable: JLatexMathDrawable?,
) {
    val id: String get() = "latex-inline-$index"
}

@Composable
private fun LatexFormulaBlock(formula: String, style: TextStyle) {
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val textSizePx = with(density) {
        if (style.fontSize.isSpecified) style.fontSize.toPx() * 1.1f else 17.dp.toPx()
    }
    val drawable = remember(formula, textSizePx, color) {
        runCatching {
            JLatexMathDrawable.builder("\\displaystyle $formula")
                .textSize(textSizePx)
                .color(color)
                .padding(4)
                .fitCanvas(true)
                .build()
        }.getOrNull()
    }
    if (drawable == null) {
        Text(formula, style = style, color = MaterialTheme.colorScheme.error)
        return
    }
    val width = with(density) { drawable.intrinsicWidth.coerceAtLeast(1).toDp() }
    val height = with(density) { drawable.intrinsicHeight.coerceAtLeast(1).toDp() }
    Box(
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Canvas(Modifier.size(width, height)) {
            drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            drawable.draw(drawContext.canvas.nativeCanvas)
        }
    }
}

/**
 * 预处理 Markdown 文本：
 * - 有连续空行段落 → 保留为 GFM 段落（空行分隔），让 mikepenz 渲染段落间距
 * - 无连续空行 → ST-style simpleLineBreaks（单换行 → 硬断行）
 */
private fun prepareMarkdown(text: String): String {
    val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    return if (normalized.contains("\n\n")) normalized
           else markdownWithHardBreaks(text)
}

/**
 * 模拟 ST Showdown 的 simpleLineBreaks：在 Markdown 段里把单个换行升级为硬断行
 * （行尾补两个空格），围栏代码块内保持原样。
 */
private fun markdownWithHardBreaks(text: String): String {
    if (!text.contains('\n')) return text
    val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    val lines = normalized.split("\n")
    val sb = StringBuilder(text.length + lines.size * 2)
    var inFence = false
    var fenceMarker = ""
    lines.forEachIndexed { i, line ->
        val trimmed = line.trimStart()
        if (!inFence && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
            inFence = true
            fenceMarker = trimmed.take(3)
        } else if (inFence && trimmed.startsWith(fenceMarker)) {
            inFence = false
        }
        sb.append(line)
        if (i != lines.lastIndex) {
            if (!inFence && line.isNotBlank()) sb.append("  ")
            sb.append('\n')
        }
    }
    return sb.toString()
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
    val lineCount = code.count { it == '\n' } + 1
    val canFold = collapsible && lineCount > threshold
    var expanded by remember(code, collapsible, threshold) { mutableStateOf(!canFold) }
    var copied by remember(code) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val colors = LocalMarkdownColors.current
    val codePad = LocalMarkdownPadding.current.codeBlock
    val labelStyle = MaterialTheme.typography.labelSmall
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Lucide.FileCode2, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                language?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
                    ?: stringResource(R.string.code_language_plain),
                style = labelStyle.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.code_block_lines_fmt, lineCount),
                style = labelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            if (canFold) {
                Icon(
                    if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    stringResource(if (expanded) R.string.collapse else R.string.expand),
                    Modifier.size(36.dp).noRippleClickable { expanded = !expanded }.padding(9.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val shown = if (expanded) code else code.lineSequence().take(threshold).joinToString("\n")
        Box(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(codePad)
        ) {
            Text(shown, style = style, color = colors.codeText)
        }
    }
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
