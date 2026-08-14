package me.rerere.fawntavern.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.fawntavern.R

@Composable
internal fun ChatConfirmationDialogs(
    showDeleteSession: Boolean,
    deleteSessionEnabled: Boolean,
    onDeleteSession: () -> Unit,
    onDismissDeleteSession: () -> Unit,
    showRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onDismissRegenerate: () -> Unit,
    showDeleteCurrentVersion: Boolean,
    onDeleteCurrentVersion: () -> Unit,
    onDismissDeleteCurrentVersion: () -> Unit,
    showDeleteAllVersions: Boolean,
    onDeleteAllVersions: () -> Unit,
    onDismissDeleteAllVersions: () -> Unit,
) {
    if (showDeleteSession) {
        ConfirmationDialog(
            titleRes = R.string.delete_chat_title,
            messageRes = R.string.delete_chat_msg,
            confirmRes = R.string.delete,
            destructive = true,
            confirmEnabled = deleteSessionEnabled,
            onConfirm = onDeleteSession,
            onDismiss = onDismissDeleteSession,
        )
    }
    if (showRegenerate) {
        ConfirmationDialog(
            titleRes = R.string.confirm_regenerate_title,
            messageRes = R.string.confirm_regenerate_msg,
            confirmRes = R.string.confirm,
            onConfirm = onRegenerate,
            onDismiss = onDismissRegenerate,
        )
    }
    if (showDeleteCurrentVersion) {
        ConfirmationDialog(
            titleRes = R.string.confirm_delete_current_version_title,
            messageRes = R.string.confirm_delete_current_version_msg,
            confirmRes = R.string.delete,
            destructive = true,
            onConfirm = onDeleteCurrentVersion,
            onDismiss = onDismissDeleteCurrentVersion,
        )
    }
    if (showDeleteAllVersions) {
        ConfirmationDialog(
            titleRes = R.string.confirm_delete_all_versions_title,
            messageRes = R.string.confirm_delete_all_versions_msg,
            confirmRes = R.string.delete,
            destructive = true,
            onConfirm = onDeleteAllVersions,
            onDismiss = onDismissDeleteAllVersions,
        )
    }
}

@Composable
private fun ConfirmationDialog(
    titleRes: Int,
    messageRes: Int,
    confirmRes: Int,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(messageRes)) },
        confirmButton = {
            TextButton(enabled = confirmEnabled, onClick = onConfirm) {
                Text(
                    stringResource(confirmRes),
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
