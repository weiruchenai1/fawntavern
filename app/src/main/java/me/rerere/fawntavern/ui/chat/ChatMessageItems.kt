package me.rerere.fawntavern.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import android.graphics.BitmapFactory
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ArrowDownToLine
import com.composables.icons.lucide.ArrowUpToLine
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.UserPen
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.Zap
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.MsgFile
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.ui.api.ProviderIcon
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.noRippleClickable

/** 消息时间戳的显示格式：xxxx-xx-xx 08:12:04 */
private fun formatMsgTime(ts: Long): String = try {
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(ts))
} catch (_: Exception) { "" }

/** 把内容的最大宽度限制为父约束的 [fraction]（用户气泡不超过行宽的 ~80%） */
private fun Modifier.maxWidthFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val cap = if (constraints.hasBoundedWidth) (constraints.maxWidth * fraction).roundToInt() else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(maxWidth = cap))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** 字号与行高等比缩放：只缩字号会让行距在放大时被字身挤掉、缩小时反而变松 */
internal fun TextStyle.scaledBy(scale: Float): TextStyle = copy(
    fontSize = fontSize * scale,
    lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
)

/** 消息里的图片附件缩略图（path 为 filesDir 相对路径，解码时降采样） */
@Composable
private fun AttachedImage(relPath: String, corner: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val bmp = remember(relPath) {
        try {
            val f = File(context.filesDir, relPath)
            if (!f.exists()) null else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 720) sample *= 2
                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (_: Exception) { null }
    } ?: return
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.maxWidthFraction(0.6f).clip(RoundedCornerShape(corner)),
        contentScale = ContentScale.FillWidth,
    )
}

/** 消息里的文件附件卡片（只展示文件名；内容在发送时内联进 prompt） */
@Composable
private fun AttachedFileCard(file: MsgFile) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Lucide.Paperclip, null, Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(file.name, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
internal fun UserMsg(
    name: String,
    text: String,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onMore: () -> Unit,
    scale: Float = 1.0f,
    avatarBitmap: android.graphics.Bitmap? = null,
    images: List<String> = emptyList(),
    files: List<MsgFile> = emptyList(),
    showAvatar: Boolean = true,
    showName: Boolean = true,
    showTimestamp: Boolean = false,
    showActions: Boolean = true,
    timestamp: Long = 0L,
    renderPrefs: RenderPrefs = RenderPrefs(),
) {
    val s8 = (Space8.value * scale).dp
    val s12 = (Space12.value * scale).dp
    val iconSz = (18f * scale).dp
    val textStyle = MaterialTheme.typography.titleMedium.scaledBy(scale)
    val timeStyle = MaterialTheme.typography.labelSmall.scaledBy(scale)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(s8)) {
        if (showName || showAvatar || showTimestamp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s8)) {
                val avatarSz = (24f * scale).dp
                // 时间戳显示在名称下方，不是右侧
                if (showName || showTimestamp) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (showName) Text(name, style = textStyle, color = MaterialTheme.colorScheme.onSurface)
                        if (showTimestamp) Text(formatMsgTime(timestamp), style = timeStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (showAvatar) {
                    Box(
                        Modifier.size(avatarSz).clip(RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap.asImageBitmap(),
                                contentDescription = "avatar",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(Lucide.UserPen, null, Modifier.size(avatarSz * 0.75f),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        images.forEach { rel -> AttachedImage(rel, s12) }
        files.forEach { f -> AttachedFileCard(f) }
        if (text.isNotBlank()) {
            // 气泡拥抱内容（markdown 走 fillWidth=false 让正文按内容撑宽，而不是固定铺满 80%）
            Box(Modifier.maxWidthFraction(0.8f).clip(RoundedCornerShape(s12)).background(MaterialTheme.colorScheme.primaryContainer).padding(s8)) {
                if (renderPrefs.markdown) {
                    MessageContent(content = text, isStreaming = false, textStyle = textStyle,
                        renderPrefs = renderPrefs, fillWidth = false)
                } else {
                    Text(text, style = textStyle, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        if (showActions) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Copy, stringResource(R.string.copy),
                    Modifier.noRippleClickable { onCopy() }
                        .padding(horizontal = s8, vertical = s8).size(iconSz),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Lucide.RotateCcw, stringResource(R.string.regenerate),
                    Modifier.noRippleClickable { onRegenerate() }
                        .padding(horizontal = s8, vertical = s8).size(iconSz),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                // 末位插槽不带 end padding，让图形与气泡右缘对齐
                Icon(Lucide.EllipsisVertical, stringResource(R.string.more),
                    Modifier.noRippleClickable { onMore() }
                        .padding(start = s8, top = s8, bottom = s8).size(iconSz),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun AIMsg(
    msg: ChatMessage,
    isStreaming: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onMore: () -> Unit,
    onPrevAlt: () -> Unit = {},
    onNextAlt: () -> Unit = {},
    onSpeak: () -> Unit = {},
    speaking: Boolean = false,
    scale: Float = 1.0f,
    regexScripts: List<CharRegex> = emptyList(),
    depth: Int? = null,
    userName: String = "",
    charName: String = "",
    showModelIcon: Boolean = true,
    showModelName: Boolean = true,
    showModelTimestamp: Boolean = false,
    showTokenUsage: Boolean = false,
    showTokenSpeed: Boolean = false,
    showGenerationTime: Boolean = false,
    autoCollapseThinking: Boolean = true,
    thinkingMarkdown: Boolean = true,
    renderPrefs: RenderPrefs = RenderPrefs(),
) {
    val s8 = (Space8.value * scale).dp
    val iconSz = (18f * scale).dp
    val textStyle = MaterialTheme.typography.titleMedium.scaledBy(scale)
    val metaStyle = MaterialTheme.typography.labelSmall.scaledBy(scale)

    val modelIconSz = (24f * scale).dp

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(s8)) {
        // 模型头部（开场白等无模型的消息不显示；开关全关时不渲染整行）。时间戳显示在模型名下方。
        if (msg.model.isNotBlank() && (showModelIcon || showModelName || showModelTimestamp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s8)) {
                if (showModelIcon) ProviderIcon(msg.model, size = modelIconSz)
                if (showModelName || showModelTimestamp) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (showModelName) {
                            Text(msg.model, style = textStyle, color = MaterialTheme.colorScheme.onSurface)
                        }
                        if (showModelTimestamp) {
                            Text(formatMsgTime(msg.ts), style = metaStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        // 深度思考 / 联网搜索时间线卡片（Kelivo 风格 Chain of Thought）
        ThoughtTimelineCard(
            msg = msg,
            isStreaming = isStreaming,
            scale = scale,
            autoCollapse = autoCollapseThinking,
            markdown = thinkingMarkdown,
            renderPrefs = renderPrefs,
        )
        MessageContent(
            content = msg.content,
            isStreaming = isStreaming,
            textStyle = textStyle,
            regexScripts = regexScripts,
            depth = depth,
            userName = userName,
            charName = charName,
            renderPrefs = renderPrefs,
        )
        // 引用胶囊卡：流式结束后显示在正文之后，点击弹出来源列表。
        // 聚合本条消息全部搜索调用的结果，按 URL 去重（多次搜索可能命中同一页面）
        val citations = remember(msg.searches) {
            msg.searches.flatMap { it.items }.distinctBy { it.url }
        }
        if (!isStreaming && citations.isNotEmpty()) {
            var showCitations by rememberSaveable { mutableStateOf(false) }
            CitationsPill(items = citations, scale = scale) { showCitations = true }
            if (showCitations) {
                CitationsSheet(items = citations, scale = scale) { showCitations = false }
            }
        }
        // 操作栏（复制/重答/翻译/更多 + 多版本切换）：流式生成中不显示，生成结束才出现
        if (!isStreaming) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 首位插槽不带 start padding，让图形与消息内容左缘对齐
                    Icon(Lucide.Copy, stringResource(R.string.copy),
                        Modifier.noRippleClickable { onCopy() }
                            .padding(top = s8, bottom = s8, end = s8).size(iconSz),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Lucide.RotateCcw, stringResource(R.string.regenerate),
                        Modifier.noRippleClickable { onRegenerate() }
                            .padding(horizontal = s8, vertical = s8).size(iconSz),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 朗读：播放中变停止图标，点击停止
                    Icon(
                        if (speaking) Lucide.CircleStop else Lucide.Volume2,
                        stringResource(if (speaking) R.string.stop_speaking else R.string.speak),
                        Modifier.noRippleClickable { onSpeak() }
                            .padding(horizontal = s8, vertical = s8).size(iconSz),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(Lucide.EllipsisVertical, stringResource(R.string.more),
                        Modifier.noRippleClickable { onMore() }
                            .padding(horizontal = s8, vertical = s8).size(iconSz),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                // 多版本左右切换（重新生成的历史版本 / 备选开场白）
                if (msg.alts.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val canPrev = msg.altIdx > 0
                        val canNext = msg.altIdx < msg.alts.lastIndex
                        Icon(Lucide.ChevronLeft, null,
                            Modifier.noRippleClickable(enabled = canPrev) { onPrevAlt() }
                                .padding(horizontal = s8, vertical = s8).size(iconSz),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canPrev) 1f else 0.35f))
                        Text("${msg.altIdx + 1}/${msg.alts.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // 末位插槽不带 end padding，让图形与右缘对齐
                        Icon(Lucide.ChevronRight, null,
                            Modifier.noRippleClickable(enabled = canNext) { onNextAlt() }
                                .padding(start = s8, top = s8, bottom = s8).size(iconSz),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canNext) 1f else 0.35f))
                    }
                }
            }
            if (showTokenUsage || showTokenSpeed || showGenerationTime) {
                MessageStatsLine(
                    msg = msg,
                    scale = scale,
                    textStyle = metaStyle,
                    showTokenUsage = showTokenUsage,
                    showTokenSpeed = showTokenSpeed,
                    showGenerationTime = showGenerationTime,
                )
            }
        }
    }
}

@Composable
private fun MessageStatsLine(
    msg: ChatMessage,
    scale: Float,
    textStyle: TextStyle,
    showTokenUsage: Boolean,
    showTokenSpeed: Boolean,
    showGenerationTime: Boolean,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val iconSize = (12f * scale).dp
    val numberFormat = remember { NumberFormat.getIntegerInstance() }
    val durationSeconds = msg.generationMs / 1000f
    val tokensPerSecond = if (msg.generationMs > 0L) {
        msg.completionTokens * 1000f / msg.generationMs
    } else 0f

    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showTokenUsage) {
            MessageStatItem(
                icon = Lucide.ArrowUpToLine,
                contentDescription = stringResource(R.string.input_tokens),
                text = stringResource(R.string.token_stats_fmt, numberFormat.format(msg.promptTokens)),
                iconSize = iconSize,
                textStyle = textStyle,
                color = color,
            )
            MessageStatItem(
                icon = Lucide.ArrowDownToLine,
                contentDescription = stringResource(R.string.output_tokens),
                text = stringResource(R.string.token_stats_fmt, numberFormat.format(msg.completionTokens)),
                iconSize = iconSize,
                textStyle = textStyle,
                color = color,
            )
        }
        if (showTokenSpeed) {
            MessageStatItem(
                icon = Lucide.Zap,
                contentDescription = stringResource(R.string.generation_speed),
                text = String.format(Locale.US, "%.1f tok/s", tokensPerSecond),
                iconSize = iconSize,
                textStyle = textStyle,
                color = color,
            )
        }
        if (showGenerationTime) {
            MessageStatItem(
                icon = Lucide.Clock,
                contentDescription = stringResource(R.string.total_duration),
                text = String.format(Locale.US, "%.1fs", durationSeconds),
                iconSize = iconSize,
                textStyle = textStyle,
                color = color,
            )
        }
    }
}

@Composable
private fun MessageStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    text: String,
    iconSize: androidx.compose.ui.unit.Dp,
    textStyle: TextStyle,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = color)
        Text(text, style = textStyle, color = color)
    }
}
