package me.rerere.fawntavern.ui.chat

import android.Manifest
import android.content.ClipData
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
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.core.content.ContextCompat
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.ui.components.noRippleClickable
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** 带固定工具栏、横向滚动和图片导出的 Markdown 表格。 */
@Composable
internal fun ChatMarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle,
    renderMath: Boolean,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
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
                resources.getString(if (saved) R.string.image_saved_to_gallery else R.string.image_save_failed),
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
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("table", tableSource)))
                    }
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

@Composable
private fun ChatMarkdownTableRow(
    content: String,
    row: ASTNode,
    tableWidth: Dp,
    style: TextStyle,
    header: Boolean,
    renderMath: Boolean,
    dividerColor: Color,
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
                    segments.forEachIndexed { segmentIndex, segment ->
                        key(segmentIndex, segment.text.hashCode(), segment.formula, segment.displayMode) {
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
