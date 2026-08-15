package me.rerere.fawntavern.ui.settings

import android.content.ClipData
import android.content.Intent
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
import androidx.core.content.FileProvider
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import me.rerere.fawntavern.R
import me.rerere.fawntavern.core.diagnostics.CrashReportStore
import me.rerere.fawntavern.data.diagnostics.RemoteDiagnostics
import me.rerere.fawntavern.ui.components.SettingsSubPage
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import java.io.File

@Composable
fun CrashReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val reportSubject = stringResource(R.string.crash_feedback_subject)
    val shareLabel = stringResource(R.string.crash_feedback_share)
    val remoteAvailable = remember(context) { RemoteDiagnostics.isAvailable(context) }
    var remoteEnabled by remember(context) {
        mutableStateOf(RemoteDiagnostics.isEnabled(context))
    }
    var report by remember(context) {
        mutableStateOf(runCatching { CrashReportStore.readLatest(context) }.getOrNull())
    }
    var reportExpanded by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
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
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        stringResource(R.string.crash_feedback_remote_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(
                            if (remoteAvailable) R.string.crash_feedback_remote_summary
                            else R.string.crash_feedback_remote_unavailable,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = remoteEnabled,
                    enabled = remoteAvailable,
                    onCheckedChange = {
                        remoteEnabled = RemoteDiagnostics.setEnabled(context, it)
                    },
                )
            }
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space8),
            ) {
                Button(
                    onClick = {
                        runCatching {
                            val shareDirectory = File(context.cacheDir, "shared-diagnostics").apply { mkdirs() }
                            val reportFile = File(shareDirectory, "FawnTavern-crash-report.txt").apply {
                                writeText(currentReport)
                            }
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                reportFile,
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, reportSubject)
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newUri(context.contentResolver, reportSubject, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, shareLabel))
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
                        if (CrashReportStore.clear(context)) {
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
