package me.rerere.fawntavern.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.model.DefaultMarkdownInlineContent
import com.mikepenz.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import java.util.Base64
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import ru.noties.jlatexmath.JLatexMathDrawable

private const val MAX_INLINE_MATH_LENGTH = 512
private const val MAX_BLOCK_MATH_LENGTH = 4_096
private val mathMarkdownParser = MarkdownParser(GFMFlavourDescriptor())

private data class EncodedMath(val formula: String, val displayMode: Boolean)

private object MathLink {
    private const val PREFIX = "latex:"

    fun encode(formula: String, displayMode: Boolean): String {
        val mode = if (displayMode) "d:" else "i:"
        return PREFIX + mode + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(formula.toByteArray(Charsets.UTF_8))
    }

    fun decode(link: String): EncodedMath? {
        if (!link.startsWith(PREFIX)) return null
        val encoded = link.removePrefix(PREFIX)
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

internal data class MathRenderSegment(
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

internal fun splitMathSegments(text: String, includeInline: Boolean = true): List<MathRenderSegment> {
    val root = runCatching { mathMarkdownParser.buildMarkdownTreeFromString(text) }.getOrNull()
        ?: return emptyList()
    val protected = protectedMathRanges(root)
    val matches = findMathDelimiters(text, includeInline).filter { match ->
        protected.none { range -> match.start <= range.last && match.endExclusive - 1 >= range.first }
    }
    if (matches.isEmpty()) return emptyList()

    val result = mutableListOf<MathRenderSegment>()
    var cursor = 0
    matches.forEach { match ->
        if (match.start > cursor) result += MathRenderSegment(text.substring(cursor, match.start), false)
        result += MathRenderSegment(match.formula.trim(), true, match.displayMode)
        cursor = match.endExclusive
    }
    if (cursor < text.length) result += MathRenderSegment(text.substring(cursor), false)
    return result.filter { it.text.isNotBlank() }
}

internal fun prepareMath(text: String): String {
    val root = runCatching { mathMarkdownParser.buildMarkdownTreeFromString(text) }.getOrNull()
        ?: return text
    val protected = protectedMathRanges(root)
    val matches = findMathDelimiters(text, includeInline = true).filter { match ->
        protected.none { range -> match.start <= range.last && match.endExclusive - 1 >= range.first }
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

private fun protectedMathRanges(root: ASTNode): List<IntRange> = buildList {
    fun collect(node: ASTNode) {
        if (node.type == MarkdownElementTypes.CODE_FENCE ||
            node.type == MarkdownElementTypes.CODE_BLOCK ||
            node.type == MarkdownElementTypes.CODE_SPAN ||
            node.type == GFMElementTypes.TABLE
        ) {
            add(node.startOffset until node.endOffset)
            return
        }
        node.children.forEach(::collect)
    }
    collect(root)
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
                findDelimitedMath(
                    text, index, "\\(", "\\)", MAX_INLINE_MATH_LENGTH, false, allowNewline = false,
                )
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
                canCloseInlineDollar(text, cursor)
            ) {
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

internal fun latexFlowHeading(
    enabled: Boolean,
    contentChildType: IElementType = MarkdownTokenTypes.ATX_CONTENT,
    style: (MarkdownComponentModel) -> TextStyle,
): MarkdownComponent = if (enabled) {
    { model -> LatexFlowMarkdownText(model, style(model), contentChildType) }
} else {
    { model -> MarkdownHeader(model.content, model.node, style(model), contentChildType) }
}

@Composable
internal fun LatexFlowMarkdownText(
    model: MarkdownComponentModel,
    style: TextStyle,
    contentChildType: IElementType? = null,
) {
    val child = contentChildType?.let { type ->
        model.node.children.firstOrNull { it.type == type }
    } ?: model.node
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
        MarkdownText(styled, node = child, style = style)
        return
    }

    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val fontSizePx = with(density) {
        if (style.fontSize.isSpecified) style.fontSize.toPx() else 16.dp.toPx()
    }
    val rendered = formulas.mapIndexed { index, (range, encoded, _) ->
        val image = remember(encoded, fontSizePx, color) {
            if (encoded.displayMode || encoded.formula.length > MAX_INLINE_MATH_LENGTH) {
                null
            } else {
                buildLatexImage(
                    formula = encoded.formula,
                    textSizePx = fontSizePx,
                    color = color,
                    maxWidthPx = 2_048,
                    maxHeightPx = 512,
                    maxPixels = 512 * 1_024,
                )
            }
        }
        FlowFormula(
            id = "latex-${range.start}-$index",
            range = range,
            formula = encoded.formula,
            displayMode = encoded.displayMode,
            image = image,
        )
    }

    val displayFormulas = rendered.filter(FlowFormula::displayMode)
    if (displayFormulas.isEmpty()) {
        InlineLatexText(styled, rendered, style, child)
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        var cursor = 0
        displayFormulas.forEach { display ->
            if (display.range.start > cursor) {
                val inlineItems = rendered.filter { item ->
                    !item.displayMode && item.range.start >= cursor && item.range.end <= display.range.start
                }
                InlineLatexText(
                    content = styled.subSequence(cursor, display.range.start),
                    formulas = inlineItems.map { it.offsetBy(-cursor) },
                    style = style,
                    node = child,
                )
            }
            LatexFormulaBlock(display.formula, style, displayMode = true)
            cursor = display.range.end
        }
        if (cursor < styled.length) {
            val inlineItems = rendered.filter { item ->
                !item.displayMode && item.range.start >= cursor && item.range.end <= styled.length
            }
            InlineLatexText(
                content = styled.subSequence(cursor, styled.length),
                formulas = inlineItems.map { it.offsetBy(-cursor) },
                style = style,
                node = child,
            )
        }
    }
}

private data class FlowFormula(
    val id: String,
    val range: AnnotatedString.Range<String>,
    val formula: String,
    val displayMode: Boolean,
    val image: RenderedLatex?,
) {
    fun offsetBy(offset: Int): FlowFormula = copy(
        range = AnnotatedString.Range(
            item = range.item,
            start = range.start + offset,
            end = range.end + offset,
            tag = range.tag,
        ),
    )
}

@Composable
private fun InlineLatexText(
    content: AnnotatedString,
    formulas: List<FlowFormula>,
    style: TextStyle,
    node: ASTNode,
) {
    if (content.isEmpty()) return
    if (formulas.isEmpty()) {
        MarkdownText(content, node = node, style = style)
        return
    }

    val density = LocalDensity.current
    val fontSizePx = with(density) {
        if (style.fontSize.isSpecified) style.fontSize.toPx() else 16.dp.toPx()
    }
    val lineHeightPx = with(density) {
        if (style.lineHeight.isSpecified) style.lineHeight.toPx() else fontSizePx * 1.35f
    }
    val maxInlineWidthPx = fontSizePx * 12f
    val inlineContent = remember(formulas, density, lineHeightPx, maxInlineWidthPx) {
        formulas.mapNotNull { item ->
            val image = item.image ?: return@mapNotNull null
            val intrinsicWidth = image.widthPx.toFloat()
            val intrinsicHeight = image.heightPx.toFloat()
            val scale = minOf(
                1f,
                lineHeightPx / intrinsicHeight,
                maxInlineWidthPx / intrinsicWidth,
            )
            val width = with(density) { (intrinsicWidth * scale).toDp().toSp() }
            val height = with(density) { lineHeightPx.toDp().toSp() }
            item.id to InlineTextContent(
                placeholder = Placeholder(
                    width = width,
                    height = height,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                Image(
                    bitmap = image.bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }.toMap()
    }
    val inheritedInlineContent = LocalMarkdownInlineContent.current
    val mergedInlineContent = remember(inheritedInlineContent, inlineContent) {
        DefaultMarkdownInlineContent(inheritedInlineContent.inlineContent + inlineContent)
    }
    val text = remember(content, formulas) {
        buildAnnotatedString {
            var cursor = 0
            formulas.sortedBy { it.range.start }.forEach { item ->
                if (item.range.start > cursor) append(content.subSequence(cursor, item.range.start))
                if (item.image == null) {
                    append("\$${item.formula}\$")
                } else {
                    appendInlineContent(item.id, "\uFFFC")
                }
                cursor = item.range.end
            }
            if (cursor < content.length) append(content.subSequence(cursor, content.length))
        }
    }
    CompositionLocalProvider(LocalMarkdownInlineContent provides mergedInlineContent) {
        MarkdownText(content = text, node = node, style = style)
    }
}

private data class RenderedLatex(
    val bitmap: ImageBitmap,
    val widthPx: Int,
    val heightPx: Int,
)

private fun buildLatexImage(
    formula: String,
    textSizePx: Float,
    color: Int,
    padding: Int = 0,
    maxWidthPx: Int = 4_096,
    maxHeightPx: Int = 2_048,
    maxPixels: Int = 2 * 1_024 * 1_024,
): RenderedLatex? = runCatching {
    val drawable = JLatexMathDrawable.builder(formula)
        .textSize(textSizePx)
        .color(color)
        .padding(padding)
        .align(JLatexMathDrawable.ALIGN_LEFT)
        .build()
    val intrinsicWidth = drawable.intrinsicWidth.coerceAtLeast(1)
    val intrinsicHeight = drawable.intrinsicHeight.coerceAtLeast(1)
    val pixelScale = kotlin.math.sqrt(
        maxPixels.toDouble() / (intrinsicWidth.toDouble() * intrinsicHeight.toDouble()),
    ).toFloat()
    val scale = minOf(
        1f,
        maxWidthPx.toFloat() / intrinsicWidth,
        maxHeightPx.toFloat() / intrinsicHeight,
        pixelScale,
    )
    val width = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
    val height = (intrinsicHeight * scale).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(AndroidCanvas(bitmap))
    RenderedLatex(bitmap.asImageBitmap(), width, height)
}.getOrNull()

@Composable
internal fun LatexFormulaBlock(
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
    val image = remember(formula, textSizePx, color, displayMode, compact) {
        if (formula.length > MAX_BLOCK_MATH_LENGTH) null else buildLatexImage(
            formula = formula,
            textSizePx = textSizePx,
            color = color,
            padding = if (displayMode && !compact) 2 else 0,
        )
    }
    if (image == null) {
        Text(if (displayMode) "\$\$$formula\$\$" else "\$$formula\$", style = style)
        return
    }
    // 保持公式固有宽度以便横向滚动，上限用于约束异常或恶意模型输出。
    val width = with(density) { image.widthPx.toDp() }
    val height = with(density) { image.heightPx.toDp() }
    Box(
        if (compact) {
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 2.dp)
        } else {
            Modifier.fillMaxWidth()
                .padding(vertical = 4.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 2.dp, vertical = 2.dp)
        },
    ) {
        Image(
            bitmap = image.bitmap,
            contentDescription = null,
            modifier = Modifier.size(width, height),
        )
    }
}
