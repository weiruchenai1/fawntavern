package me.rerere.fawntavern.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.update.GitHubReleaseChecker
import me.rerere.fawntavern.data.update.UpdateCheckResult

private sealed interface UpdateDialogState {
    data object Checking : UpdateDialogState
    data object UpToDate : UpdateDialogState
    data object Failed : UpdateDialogState
    data class Available(val result: UpdateCheckResult.Available) : UpdateDialogState
}

@Composable
internal fun UpdateCheckDialog(
    currentVersion: String,
    onDismiss: () -> Unit,
) {
    val checker = remember { GitHubReleaseChecker() }
    val uriHandler = LocalUriHandler.current
    var retryKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<UpdateDialogState>(UpdateDialogState.Checking) }

    LaunchedEffect(currentVersion, retryKey) {
        state = UpdateDialogState.Checking
        state = try {
            when (val result = checker.check(currentVersion)) {
                UpdateCheckResult.UpToDate -> UpdateDialogState.UpToDate
                is UpdateCheckResult.Available -> UpdateDialogState.Available(result)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            UpdateDialogState.Failed
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (state) {
                        UpdateDialogState.Checking -> R.string.check_update
                        UpdateDialogState.UpToDate -> R.string.update_latest_title
                        UpdateDialogState.Failed -> R.string.update_failed_title
                        is UpdateDialogState.Available -> R.string.update_available_title
                    },
                ),
            )
        },
        text = {
            when (val currentState = state) {
                UpdateDialogState.Checking -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.update_checking))
                }
                UpdateDialogState.UpToDate -> Text(
                    stringResource(R.string.update_latest_msg, currentVersion),
                    style = MaterialTheme.typography.bodyMedium,
                )
                UpdateDialogState.Failed -> Text(stringResource(R.string.update_failed_message))
                is UpdateDialogState.Available -> Text(
                    stringResource(
                        R.string.update_available_message,
                        currentState.result.latestVersion,
                        currentVersion,
                    ),
                )
            }
        },
        confirmButton = {
            when (val currentState = state) {
                UpdateDialogState.Checking -> Unit
                UpdateDialogState.UpToDate -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.confirm))
                }
                UpdateDialogState.Failed -> TextButton(onClick = { retryKey++ }) {
                    Text(stringResource(R.string.retry))
                }
                is UpdateDialogState.Available -> TextButton(
                    onClick = {
                        uriHandler.openUri(currentState.result.downloadUrl)
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.download))
                }
            }
        },
        dismissButton = {
            if (state is UpdateDialogState.Available || state is UpdateDialogState.Failed) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}
