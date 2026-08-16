package me.rerere.fawntavern.ui.settings

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.ScrollText
import com.composables.icons.lucide.Share2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.rerere.fawntavern.R
import me.rerere.fawntavern.core.diagnostics.SafeLog
import me.rerere.fawntavern.core.diagnostics.SafeLogEntry
import me.rerere.fawntavern.core.diagnostics.SafeLogLevel
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.AppTopBar
import me.rerere.fawntavern.ui.components.SettingsSubPage
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import kotlinx.coroutines.launch

@Composable
fun LogsScreen(
    onBack: () -> Unit,
    onOpenSystemLog: () -> Unit,
    onOpenPromptLog: () -> Unit,
) {
    BackHandler(onBack = onBack)
    SettingsSubPage(stringResource(R.string.logs), onBack) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            LogNavigationRow(
                icon = Lucide.ScrollText,
                label = stringResource(R.string.system_log),
                onClick = onOpenSystemLog,
            )
            LogNavigationRow(
                icon = Lucide.FileText,
                label = stringResource(R.string.debug_log),
                onClick = onOpenPromptLog,
            )
        }
    }
}

@Composable
private fun LogNavigationRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = Space12, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Space12))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Lucide.ChevronRight,
            null,
            Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SystemLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val shareTitle = stringResource(R.string.share)
    val shareSubject = stringResource(R.string.system_log_share_subject)
    var revision by remember { mutableIntStateOf(0) }
    val entries = remember(revision) { SafeLog.snapshot() }
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(stringResource(R.string.system_log), onBack) {
                AppIconButton(
                    icon = Lucide.RefreshCw,
                    contentDescription = stringResource(R.string.refresh),
                    onClick = { revision++ },
                    size = 32.dp,
                    iconSize = 20.dp,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space16, vertical = Space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pluralStringResource(R.plurals.system_log_entry_count, entries.size, entries.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (entries.isNotEmpty()) {
                    AppIconButton(
                        icon = Lucide.Copy,
                        contentDescription = stringResource(R.string.copy),
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("system log", SafeLog.format(entries))),
                                )
                            }
                            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                        },
                        size = 32.dp,
                        iconSize = 20.dp,
                    )
                    AppIconButton(
                        icon = Lucide.Share2,
                        contentDescription = shareTitle,
                        onClick = {
                            runCatching {
                                shareDiagnosticsText(
                                    context = context,
                                    fileName = "FawnTavern-system-log.txt",
                                    content = SafeLog.format(entries),
                                    subject = shareSubject,
                                    chooserTitle = shareTitle,
                                )
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    R.string.system_log_share_failed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        size = 32.dp,
                        iconSize = 20.dp,
                    )
                    TextButton(
                        onClick = {
                            SafeLog.clear()
                            revision++
                        },
                    ) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }

            if (entries.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(Space16),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.system_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Space16,
                        top = Space8,
                        end = Space16,
                        bottom = Space16,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Space12),
                ) {
                    items(entries) { entry -> SystemLogEntryCard(entry) }
                }
            }
        }
    }
}

@Composable
private fun SystemLogEntryCard(entry: SafeLogEntry) {
    val levelColor: Color
    val levelLabel: String
    when (entry.level) {
        SafeLogLevel.ERROR -> {
            levelColor = MaterialTheme.colorScheme.error
            levelLabel = stringResource(R.string.log_level_error)
        }
        SafeLogLevel.WARNING -> {
            levelColor = MaterialTheme.colorScheme.tertiary
            levelLabel = stringResource(R.string.log_level_warning)
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Space12),
        verticalArrangement = Arrangement.spacedBy(Space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                levelLabel,
                style = MaterialTheme.typography.labelMedium,
                color = levelColor,
            )
            Spacer(Modifier.width(Space8))
            Text(
                entry.tag,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatLogTime(entry.timestampMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SelectionContainer {
            Text(
                buildString {
                    append(entry.event)
                    entry.errorType?.let { append("\n").append(it) }
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun formatLogTime(timestampMillis: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))
