package me.rerere.stapp.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.UserPen
import java.io.File
import kotlin.math.roundToInt
import me.rerere.stapp.R
import me.rerere.stapp.data.chat.ChatMessage
import me.rerere.stapp.data.chat.MsgFile
import me.rerere.stapp.data.character.CharRegex
import me.rerere.stapp.ui.api.ProviderIcon
import me.rerere.stapp.ui.components.Space8
import me.rerere.stapp.ui.components.Space12

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
) {
    val s8 = (Space8.value * scale).dp
    val s12 = (Space12.value * scale).dp
    val iconSz = (18f * scale).dp
    val textStyle = MaterialTheme.typography.titleMedium.scaledBy(scale)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(s8)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s8)) {
            val avatarSz = (24f * scale).dp
            Text(name, style = textStyle, color = MaterialTheme.colorScheme.onSurface)
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
        images.forEach { rel -> AttachedImage(rel, s12) }
        files.forEach { f -> AttachedFileCard(f) }
        if (text.isNotBlank()) {
            Box(Modifier.maxWidthFraction(0.8f).clip(RoundedCornerShape(s12)).background(MaterialTheme.colorScheme.primaryContainer).padding(s8)) {
                Text(text, style = textStyle, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Copy, stringResource(R.string.copy),
                Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onCopy() }
                    .padding(horizontal = s8, vertical = s8).size(iconSz),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Lucide.RotateCcw, stringResource(R.string.regenerate),
                Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onRegenerate() }
                    .padding(horizontal = s8, vertical = s8).size(iconSz),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            // 末位插槽不带 end padding，让图形与气泡右缘对齐
            Icon(Lucide.EllipsisVertical, stringResource(R.string.more),
                Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onMore() }
                    .padding(start = s8, top = s8, bottom = s8).size(iconSz),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    scale: Float = 1.0f,
    regexScripts: List<CharRegex> = emptyList(),
    depth: Int? = null,
    userName: String = "",
    charName: String = "",
) {
    val s8 = (Space8.value * scale).dp
    val iconSz = (18f * scale).dp
    val textStyle = MaterialTheme.typography.titleMedium.scaledBy(scale)
    val reasoningStyle = MaterialTheme.typography.bodySmall.scaledBy(scale)

    val modelIconSz = (24f * scale).dp

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(s8)) {
        // 模型头部（开场白等无模型的消息不显示）
        if (msg.model.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s8)) {
                ProviderIcon(msg.model, size = modelIconSz)
                Text(msg.model, style = textStyle, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (msg.reasoning.isNotBlank()) {
            // rememberSaveable + 条目 key（msg.ts）：滚出屏幕被回收再滚回来，展开状态不丢，
            // 条目高度也不会因此在回来时和离开时不一致
            var expanded by rememberSaveable { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(s8),
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { expanded = !expanded },
            ) {
                Text(
                    stringResource(R.string.thinking_took_fmt, msg.reasoningMs / 1000.0f),
                    style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(if (expanded) Lucide.ChevronDown else Lucide.ChevronRight, null,
                    Modifier.size(iconSz), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                Text(
                    msg.reasoning, style = reasoningStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(s8))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(s8),
                )
            }
        }
        MessageContent(
            content = msg.content,
            isStreaming = isStreaming,
            textStyle = textStyle,
            regexScripts = regexScripts,
            depth = depth,
            userName = userName,
            charName = charName,
        )
        // 操作栏（复制/重答/翻译/更多 + 多版本切换）：流式生成中不显示，生成结束才出现
        if (!isStreaming) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 首位插槽不带 start padding，让图形与消息内容左缘对齐
                    Icon(Lucide.Copy, stringResource(R.string.copy),
                        Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onCopy() }
                            .padding(top = s8, bottom = s8, end = s8).size(iconSz),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Lucide.RotateCcw, stringResource(R.string.regenerate),
                        Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onRegenerate() }
                            .padding(horizontal = s8, vertical = s8).size(iconSz),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Lucide.EllipsisVertical, stringResource(R.string.more),
                        Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onMore() }
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
                            Modifier.clickable(enabled = canPrev, indication = null, interactionSource = remember { MutableInteractionSource() }) { onPrevAlt() }
                                .padding(horizontal = s8, vertical = s8).size(iconSz),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canPrev) 1f else 0.35f))
                        Text("${msg.altIdx + 1}/${msg.alts.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // 末位插槽不带 end padding，让图形与右缘对齐
                        Icon(Lucide.ChevronRight, null,
                            Modifier.clickable(enabled = canNext, indication = null, interactionSource = remember { MutableInteractionSource() }) { onNextAlt() }
                                .padding(start = s8, top = s8, bottom = s8).size(iconSz),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canNext) 1f else 0.35f))
                    }
                }
            }
        }
    }
}
