package me.rerere.stapp.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.AlignLeft
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCirclePlus
import com.composables.icons.lucide.Package
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.X
import me.rerere.stapp.R
import me.rerere.stapp.ui.api.ProviderIcon

private val Space8 = 8.dp
private val Space12 = 12.dp
private val Space16 = 16.dp

internal data class Attachment(val uri: Uri, val isImage: Boolean)

@Composable
internal fun ChatTopBar(title: String, subtitle: String, onDrawer: () -> Unit, onNewChat: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding().padding(vertical = Space8),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Lucide.AlignLeft, "Menu",
            Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDrawer() }
                .padding(start = Space16, top = Space12, bottom = Space12, end = Space12).size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            Lucide.MessageCirclePlus, "New Chat",
            Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onNewChat() }
                .padding(start = Space12, top = Space12, bottom = Space12, end = Space16).size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun ChatBottomArea(
    text: String,
    onTextChange: (String) -> Unit,
    attachments: List<Attachment>,
    onRemoveAttachment: (Attachment) -> Unit,
    showAttachment: Boolean,
    onToggleAttachment: () -> Unit,
    onSend: () -> Unit,
    currentModelId: String = "",
    generating: Boolean = false,
    onStop: () -> Unit = {},
    onSelectModel: () -> Unit = {},
    onCamera: () -> Unit = {},
    onGallery: () -> Unit = {},
    onFile: () -> Unit = {},
) {
    val hasContent = text.isNotBlank() || attachments.isNotEmpty()
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = Space16, vertical = Space8)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            // 附件预览：位于输入框上方，可横向滚动
            if (attachments.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(start = Space12, end = Space12, top = Space12),
                    horizontalArrangement = Arrangement.spacedBy(Space8),
                ) {
                    itemsIndexed(attachments) { _, att ->
                        if (att.isImage) {
                            val bmp = remember(att.uri) {
                                try {
                                    context.contentResolver.openInputStream(att.uri)?.use { BitmapFactory.decodeStream(it) }
                                } catch (_: Exception) { null }
                            }
                            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                if (bmp != null) {
                                    Image(bitmap = bmp.asImageBitmap(), contentDescription = null,
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                                Icon(Lucide.X, "Remove",
                                    Modifier.size(16.dp).align(Alignment.TopEnd)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(bottomStart = 4.dp))
                                        .clickable { onRemoveAttachment(att) },
                                    tint = MaterialTheme.colorScheme.onSurface)
                            }
                        } else {
                            // 文件卡片：140×56dp，右上角移除 X 的样式与图片缩略图的完全一致
                            val fileInfo = remember(att.uri) { getFileInfo(context, att.uri) }
                            Box(Modifier.height(56.dp).width(140.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Column(Modifier.fillMaxSize().padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 20.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(fileInfo.first, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(fileInfo.second, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Lucide.X, "Remove",
                                    Modifier.size(16.dp).align(Alignment.TopEnd)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(bottomStart = 4.dp))
                                        .clickable { onRemoveAttachment(att) },
                                    tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space12, vertical = if (attachments.isEmpty()) 12.dp else 8.dp).heightIn(min = 40.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(stringResource(R.string.input_hint), style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        inner()
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(start = Space12, end = Space12, bottom = Space8), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 模型选择器：只显示提供商图标（随所选模型变化）。
                    // 该插槽不带 start padding：让这组图标的左缘与输入框文本的 12dp 内边距对齐
                    Box(
                        Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onSelectModel() }
                            .padding(top = Space12, bottom = Space12, end = Space8).size(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (currentModelId.isNotBlank()) {
                            ProviderIcon(currentModelId, size = 24.dp)
                        } else {
                            Icon(Lucide.Package, stringResource(R.string.select_model),
                                Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space8), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (showAttachment) Lucide.X else Lucide.Plus, if (showAttachment) "Close" else "Add",
                        Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onToggleAttachment() }
                            .padding(vertical = Space12, horizontal = Space8).size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        Modifier.size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (generating || hasContent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                            .clickable { if (generating) onStop() else onSend() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (generating) Lucide.Square else Lucide.ArrowUp,
                            if (generating) stringResource(R.string.stop) else "Send",
                            Modifier.size(if (generating) 16.dp else 22.dp),
                            tint = if (generating || hasContent) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        AnimatedVisibility(visible = showAttachment) {
            Row(Modifier.fillMaxWidth().padding(top = Space8), horizontalArrangement = Arrangement.Center) {
                AttachBtn(Lucide.Camera, stringResource(R.string.take_photo), onClick = onCamera); Spacer(Modifier.width(Space8))
                AttachBtn(Lucide.Image, stringResource(R.string.gallery), onClick = onGallery); Spacer(Modifier.width(Space8))
                AttachBtn(Lucide.Paperclip, stringResource(R.string.file), onClick = onFile)
            }
        }
    }
}

@Composable
private fun AttachBtn(icon: ImageVector, label: String, onClick: () -> Unit = {}) {
    Column(Modifier.width(109.dp).height(80.dp).clip(RoundedCornerShape(Space8)).background(MaterialTheme.colorScheme.primaryContainer).clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(Space8))
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

private fun getFileInfo(context: Context, uri: Uri): Pair<String, String> {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) {
                    val dn = cursor.getString(nameIdx)
                    if (!dn.isNullOrBlank()) name = dn
                }
                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
    } catch (_: Exception) {}
    val ext = name.substringAfterLast('.', "").uppercase()
    val fmt = if (ext.isNotBlank()) ext else context.getString(R.string.unknown_format)
    val sizeStr = when {
        size < 1024 -> "${size}B"
        size < 1024 * 1024 -> "${size / 1024}KB"
        else -> "%.1fMB".format(size.toDouble() / (1024 * 1024))
    }
    return Pair(name, if (size > 0) "$fmt  $sizeStr" else fmt)
}
