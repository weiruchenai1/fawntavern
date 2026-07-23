package me.rerere.fawntavern.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.DefaultMarkdownAnimation
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownPadding
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
    // 全部走 mikepenz Markdown。mikepenz 原生支持 ``` 代码围栏、**粗体**、*斜体*、链接等 GFM 语法。
    // HTML 标签（如 <b>/<i>/<StatusBlock>）如果被 mikepenz 识别为 HTML 则会渲染，否则显示源码。
    //
    // 同步解析：Markdown(content) 重载默认异步解析，消息会先以极小高度上屏、几帧后才长到真实高度，
    // 流式增长与跳转/切分支/重试后的重新定位会因此肉眼可见地抖动。此处同步解析一次成型，
    // remember(processed) 保证每段内容只解析一次（流式期间即每帧节流刷新时解析一次）。
    val markdownState = remember(processed) {
        val md = prepareMarkdown(processed)
        val handler = ReferenceLinkHandlerImpl()
        try {
            // linksLookedUp = false：链接定义节点由渲染器在组合期自行登记到 handler
            State.Success(markdownParser.buildMarkdownTreeFromString(md), md, false, handler)
        } catch (e: Throwable) {
            State.Error(e, handler)
        }
    }
    Markdown(
        state = markdownState,
        typography = chatMarkdownTypography(textStyle),
        padding = markdownPadding(block = 6.dp),
        modifier = modifier.fillMaxWidth(),
        animations = noTextAnimations,
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
