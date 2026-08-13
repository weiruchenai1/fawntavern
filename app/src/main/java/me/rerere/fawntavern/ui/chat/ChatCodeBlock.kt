package me.rerere.fawntavern.ui.chat

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.ui.components.noRippleClickable

/** Chat-oriented code surface with a stable metadata/action bar and optional line-limit folding. */
@Suppress("DEPRECATION")
@Composable
internal fun ChatCodeBlock(
    code: String,
    language: String?,
    style: TextStyle,
    collapsible: Boolean,
    threshold: Int,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val lineCount = code.count { it == '\n' } + 1
    val canFold = lineCount > threshold
    // null keeps the automatic policy until the user explicitly expands or collapses the block.
    var expansionOverride by remember(collapsible, threshold) { mutableStateOf<Boolean?>(null) }
    val expanded = expansionOverride ?: !(collapsible && canFold)
    var copied by remember(code) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
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
                resources.getString(if (saved) R.string.file_saved else R.string.file_save_failed),
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
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                    }
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
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                visibleCode,
                style = style.copy(color = colors.codeText),
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
