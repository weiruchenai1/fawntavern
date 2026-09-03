package me.rerere.fawntavern.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import me.rerere.fawntavern.R
import me.rerere.fawntavern.ui.components.SettingsSubPage
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12

@Composable
fun CrashReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { DiagnosticsController(AndroidDiagnosticsDataSource(context)) }
    val initialState = remember(controller) { controller.load() }
    val reportSubject = stringResource(R.string.crash_feedback_subject)
    val shareLabel = stringResource(R.string.crash_feedback_share)
    val remoteAvailable = initialState.remoteAvailable
    var remoteEnabled by remember(context) {
        mutableStateOf(initialState.remoteEnabled)
    }
    var report by remember(context) {
        mutableStateOf(initialState.report)
    }
    var reportExpanded by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    val safeLogCount = initialState.safeLogCount
    var includeSafeLogs by remember { mutableStateOf(safeLogCount > 0) }
    BackHandler(onBack = onBack)

    SettingsSubPage(stringResource(R.string.crash_feedback), onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = Space12, vertical = Space8),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.crash_feedback_remote_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = remoteEnabled,
                    enabled = remoteAvailable,
                    onCheckedChange = {
                        remoteEnabled = controller.setRemoteEnabled(it)
                    },
                )
            }
            Text(
                text = stringResource(R.string.crash_feedback_remote_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        val currentReport = report
        if (currentReport == null) {
            Text(
                stringResource(R.string.crash_feedback_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val reportHasMoreLines = remember(currentReport) {
                currentReport.lineSequence().take(21).count() > 20
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = Space12, vertical = Space8),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (reportHasMoreLines) {
                                Modifier.clickable { reportExpanded = !reportExpanded }
                            } else {
                                Modifier
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.crash_feedback),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (reportHasMoreLines) {
                        Icon(
                            if (reportExpanded) Lucide.ChevronUp else Lucide.ChevronDown,
                            contentDescription = null,
                        )
                    }
                }
                SelectionContainer {
                    Text(
                        currentReport,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (reportExpanded) Int.MAX_VALUE else 20,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = safeLogCount > 0) {
                        includeSafeLogs = !includeSafeLogs
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = includeSafeLogs,
                    enabled = safeLogCount > 0,
                    onCheckedChange = { includeSafeLogs = it },
                )
                Text(
                    stringResource(R.string.crash_feedback_include_safe_logs, safeLogCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (safeLogCount > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space8),
            ) {
                Button(
                    onClick = {
                        runCatching {
                            val safeLogs = if (includeSafeLogs) controller.safeLogsText() else ""
                            val shareContent = buildString {
                                append(currentReport.trimEnd())
                                if (safeLogs.isNotEmpty()) {
                                    appendLine()
                                    appendLine()
                                    append(safeLogs)
                                }
                            }
                            shareDiagnosticsText(
                                context = context,
                                fileName = "FawnTavern-crash-report.txt",
                                content = shareContent,
                                subject = reportSubject,
                                chooserTitle = shareLabel,
                            )
                        }.onFailure {
                            Toast.makeText(
                                context,
                                R.string.crash_feedback_share_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Lucide.Share2, null)
                    Text(shareLabel, Modifier.padding(start = Space8))
                }
                OutlinedButton(
                    onClick = { showClearConfirmation = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Lucide.Trash2, null)
                    Text(stringResource(R.string.clear), Modifier.padding(start = Space8))
                }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.crash_feedback_clear_title)) },
            text = { Text(stringResource(R.string.crash_feedback_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (controller.clearReport()) {
                            report = null
                            reportExpanded = false
                        }
                        showClearConfirmation = false
                    },
                ) {
                    Text(
                        stringResource(R.string.clear_category_btn),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
