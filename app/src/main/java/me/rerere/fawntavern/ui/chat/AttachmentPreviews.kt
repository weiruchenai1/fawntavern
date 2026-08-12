package me.rerere.fawntavern.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.ImageOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.chat.MsgFile

private val AttachmentShape = RoundedCornerShape(8.dp)
private val AttachmentHeight = 56.dp

/** 附件按类型分行展示：第一行图片、第二行其它文件，各自横向滚动 */
@Composable
internal fun InputAttachmentRow(
    attachments: List<Attachment>,
    onRemove: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val images = attachments.filter { it.isImage }
    val files = attachments.filterNot { it.isImage }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (images.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // key 带索引：多选可能重复选择同一文件，同 URI 会撞 key 导致 LazyRow 崩溃
                itemsIndexed(images, key = { i, a -> "img:$i:${a.uri}" }) { _, attachment ->
                    ImageAttachmentTile(model = attachment.uri, onRemove = { onRemove(attachment) })
                }
            }
        }
        if (files.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(files, key = { i, a -> "file:$i:${a.uri}" }) { _, attachment ->
                    InputFileAttachmentTile(attachment.uri, onRemove = { onRemove(attachment) })
                }
            }
        }
    }
}

/** 已发送消息的附件展示：同样图片一行、文件一行（用户气泡右对齐） */
@Composable
internal fun MessageAttachmentRow(
    images: List<String>,
    files: List<MsgFile>,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty() && files.isEmpty()) return
    val context = LocalContext.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (images.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                items(images, key = { "image:$it" }) { path ->
                    ImageAttachmentTile(model = java.io.File(context.filesDir, path))
                }
            }
        }
        if (files.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                items(files, key = { "file:${it.path}:${it.name}" }) { file ->
                    val stored = java.io.File(context.filesDir, file.path)
                    FileAttachmentTile(
                        name = file.name,
                        meta = fileMeta(context, file.name, stored.takeIf { it.exists() }?.length() ?: 0L),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageAttachmentTile(
    model: Any,
    onRemove: (() -> Unit)? = null,
) {
    var showPreview by remember(model) { mutableStateOf(false) }
    Box(
        Modifier.size(AttachmentHeight)
            .clip(AttachmentShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { showPreview = true },
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            error = androidx.compose.ui.graphics.vector.rememberVectorPainter(Lucide.ImageOff),
        )
        if (onRemove != null) RemoveAttachmentButton(onRemove)
    }
    if (showPreview) {
        ImagePreviewDialog(model = model, onDismiss = { showPreview = false })
    }
}

@Composable
private fun InputFileAttachmentTile(uri: Uri, onRemove: () -> Unit) {
    val context = LocalContext.current
    val info by produceState(initialValue = fallbackFileInfo(context, uri), uri) {
        value = withContext(Dispatchers.IO) { queryFileInfo(context, uri) }
    }
    FileAttachmentTile(name = info.first, meta = info.second, onRemove = onRemove)
}

@Composable
private fun FileAttachmentTile(
    name: String,
    meta: String,
    onRemove: (() -> Unit)? = null,
) {
    Box(
        Modifier.height(AttachmentHeight).width(160.dp)
            .clip(AttachmentShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxSize().padding(
                start = 10.dp,
                end = if (onRemove == null) 10.dp else 26.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Lucide.FileText,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onRemove != null) RemoveAttachmentButton(onRemove)
    }
}

@Composable
private fun BoxScope.RemoveAttachmentButton(onClick: () -> Unit) {
    Box(
        Modifier.size(24.dp).align(Alignment.TopEnd)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(bottomStart = 6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Lucide.X, "Remove", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

private fun fallbackFileInfo(context: Context, uri: Uri): Pair<String, String> {
    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    return name to fileMeta(context, name, 0L)
}

private fun queryFileInfo(context: Context, uri: Uri): Pair<String, String> {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { name = it }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    } catch (_: Exception) {
        // Keep the URI-derived fallback when a document provider does not expose metadata.
    }
    return name to fileMeta(context, name, size)
}

private fun fileMeta(context: Context, name: String, size: Long): String {
    val extension = name.substringAfterLast('.', "").uppercase()
        .ifBlank { context.getString(R.string.unknown_format) }
    val sizeText = when {
        size <= 0L -> ""
        size < 1024L -> "${size} B"
        size < 1024L * 1024L -> "${size / 1024L} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", size.toDouble() / (1024.0 * 1024.0))
    }
    return if (sizeText.isBlank()) extension else "$extension  $sizeText"
}
