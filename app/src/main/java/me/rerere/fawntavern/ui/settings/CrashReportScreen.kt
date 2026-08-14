package me.rerere.fawntavern.ui.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import me.rerere.fawntavern.R
import me.rerere.fawntavern.core.diagnostics.CrashReportStore
import me.rerere.fawntavern.data.diagnostics.RemoteDiagnostics
import me.rerere.fawntavern.ui.components.SettingsSubPage
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12

@Composable
fun CrashReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val remoteAvailable = remember(context) { RemoteDiagnostics.isAvailable(context) }
    var remoteEnabled by remember(context) {
        mutableStateOf(RemoteDiagnostics.isEnabled(context))
    }
    var report by remember(context) {
        mutableStateOf(runCatching { CrashReportStore.readLatest(context) }.getOrNull())
    }
    BackHandler(onBack = onBack)

    SettingsSubPage(stringResource(R.string.crash_feedback), onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(Space12),
            verticalArrangement = Arrangement.spacedBy(Space8),
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
                        remoteEnabled = RemoteDiagnostics.setEnabled(context, it)
                    },
                )
            }
            Text(
                stringResource(
                    if (remoteAvailable) R.string.crash_feedback_remote_summary
                    else R.string.crash_feedback_remote_unavailable,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            SelectionContainer {
                Text(
                    currentReport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(Space12),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space8),
            ) {
                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_feedback_subject))
                            .putExtra(Intent.EXTRA_TEXT, currentReport)
                        context.startActivity(
                            Intent.createChooser(send, context.getString(R.string.crash_feedback_share)),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Lucide.Share2, null)
                    Text(stringResource(R.string.crash_feedback_share), Modifier.padding(start = Space8))
                }
                OutlinedButton(
                    onClick = {
                        if (CrashReportStore.clear(context)) report = null
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Lucide.Trash2, null)
                    Text(stringResource(R.string.clear), Modifier.padding(start = Space8))
                }
            }
        }
    }
}
