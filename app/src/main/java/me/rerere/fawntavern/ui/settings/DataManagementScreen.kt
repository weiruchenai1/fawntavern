package me.rerere.fawntavern.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowDownToLine
import com.composables.icons.lucide.ArrowUpToLine
import com.composables.icons.lucide.SquareLibrary
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Package
import com.composables.icons.lucide.ScrollText
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Smile
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.backup.AppBackup
import me.rerere.fawntavern.ui.components.SettingsSubPage
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16

@Composable
private fun DataCategory.label(): String = stringResource(labelResId)

private data class DataCategory(
    val key: DataCategoryKey,
    val labelResId: Int,
    val icon: @Composable () -> Unit,
)

private val credentialBackupSections = setOf(
    AppBackup.Section.API_CONFIG,
    AppBackup.Section.SEARCH_CONFIG,
    AppBackup.Section.TTS_CONFIG,
)

private fun AppBackup.Section.labelResId(): Int = when (this) {
    AppBackup.Section.CHARACTERS -> R.string.backup_section_characters
    AppBackup.Section.PRESETS -> R.string.backup_section_presets
    AppBackup.Section.WORLDBOOKS -> R.string.backup_section_worldbooks
    AppBackup.Section.CHATS -> R.string.backup_section_chats
    AppBackup.Section.API_CONFIG -> R.string.backup_section_api
    AppBackup.Section.SEARCH_CONFIG -> R.string.backup_section_search
    AppBackup.Section.TTS_CONFIG -> R.string.backup_section_tts
    AppBackup.Section.AVATAR -> R.string.backup_section_avatar
}

@Composable
fun DataManagementScreen(
    onBack: () -> Unit,
    destructiveActionsEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val controller = remember(context) {
        DataManagementController(AndroidDataManagementDataSource(context))
    }
    val backupImportedMessage: (Int, Int) -> String = { files, sessions ->
        resources.getQuantityString(R.plurals.backup_files, files, files) +
            resources.getQuantityString(R.plurals.backup_sessions_suffix, sessions, sessions)
    }

    var showClearCategory by remember { mutableStateOf<DataCategory?>(null) }
    var showClearAll by remember { mutableStateOf(false) }
    var showResetApi by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var showExportSelection by remember { mutableStateOf(false) }
    var exportSections by remember { mutableStateOf(AppBackup.defaultExportSections) }
    var pendingExportSections by remember { mutableStateOf<Set<AppBackup.Section>>(emptySet()) }
    var pendingImport by remember { mutableStateOf<PendingBackup?>(null) }
    var importAvailableSections by remember { mutableStateOf<Set<AppBackup.Section>>(emptySet()) }
    var importSections by remember { mutableStateOf<Set<AppBackup.Section>>(emptySet()) }
    val currentPendingImport by rememberUpdatedState(pendingImport)

    DisposableEffect(controller) {
        onDispose { controller.discard(currentPendingImport) }
    }

    val categories = remember {
        listOf(
            DataCategory(
                key = DataCategoryKey.CHARACTERS,
                labelResId = R.string.characters,
                icon = {
                    Icon(Lucide.Smile, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            ),
            DataCategory(
                key = DataCategoryKey.PRESETS,
                labelResId = R.string.presets,
                icon = {
                    Icon(Lucide.SlidersHorizontal, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            ),
            DataCategory(
                key = DataCategoryKey.WORLDBOOKS,
                labelResId = R.string.world_books,
                icon = {
                    Icon(Lucide.SquareLibrary, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            ),
            DataCategory(
                key = DataCategoryKey.CHATS,
                labelResId = R.string.chat_history,
                icon = {
                    Icon(Lucide.MessageCircle, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            ),
            DataCategory(
                key = DataCategoryKey.SYSTEM_LOGS,
                labelResId = R.string.system_log,
                icon = {
                    Icon(Lucide.ScrollText, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            ),
        )
    }

    // 加载各分类的条目数与占用大小
    var snapshot by remember {
        mutableStateOf(DataManagementSnapshot(emptyList(), apiCount = 0))
    }

    fun refresh() {
        scope.launch {
            working = true
            try {
                snapshot = withContext(Dispatchers.IO) { controller.snapshot() }
            } finally {
                working = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    showClearCategory?.let { cat ->
        val cnt = snapshot.categories.find { it.key == cat.key }?.itemCount ?: 0
        AlertDialog(
            onDismissRequest = { showClearCategory = null },
            icon = {
                Icon(Lucide.TriangleAlert, null, Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error)
            },
            title = { Text(stringResource(R.string.clear_category_title_fmt, cat.label())) },
            text = { Text(stringResource(R.string.clear_category_msg_fmt, cnt, cat.label())) },
            confirmButton = {
                TextButton(enabled = destructiveActionsEnabled, onClick = {
                    scope.launch {
                        working = true
                        try {
                            snapshot = withContext(Dispatchers.IO) { controller.clear(cat.key) }
                            Toast.makeText(
                                context,
                                resources.getString(R.string.toast_cleared_fmt, resources.getString(cat.labelResId)),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } finally {
                            working = false
                        }
                    }
                    showClearCategory = null
                }) { Text(stringResource(R.string.clear_category_btn), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearCategory = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            icon = {
                Icon(Lucide.TriangleAlert, null, Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error)
            },
            title = { Text(stringResource(R.string.clear_all_title)) },
            text = { Text(stringResource(R.string.clear_all_msg)) },
            confirmButton = {
                TextButton(enabled = destructiveActionsEnabled, onClick = {
                    scope.launch {
                        working = true
                        try {
                            snapshot = withContext(Dispatchers.IO) { controller.clearAll() }
                            Toast.makeText(context, resources.getString(R.string.toast_cleared_all), Toast.LENGTH_SHORT).show()
                        } finally {
                            working = false
                        }
                    }
                    showClearAll = false
                }) { Text(stringResource(R.string.clear_all_btn), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearAll = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showResetApi) {
        AlertDialog(
            onDismissRequest = { showResetApi = false },
            title = { Text(stringResource(R.string.reset_api_title)) },
            text = { Text(stringResource(R.string.reset_api_msg)) },
            confirmButton = {
                TextButton(enabled = destructiveActionsEnabled, onClick = {
                    showResetApi = false
                    scope.launch {
                        working = true
                        try {
                            val apiCount = withContext(Dispatchers.IO) { controller.resetApi() }
                            snapshot = snapshot.copy(apiCount = apiCount)
                            Toast.makeText(context, resources.getString(R.string.toast_api_reset), Toast.LENGTH_SHORT).show()
                        } finally {
                            working = false
                        }
                    }
                }) { Text(stringResource(R.string.reset_btn), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showResetApi = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // 导出全部（SAF CreateDocument → 打包为 ZIP）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                working = true
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            controller.export(out, pendingExportSections)
                        } ?: error("Unable to create backup")
                    }
                    Toast.makeText(context, resources.getString(R.string.toast_export_success), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, resources.getString(R.string.toast_export_failed_fmt, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
                working = false
            }
        }
    }

    // 导入备份（SAF OpenDocument → 解包 ZIP，按目录前缀还原）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                    working = true
                    try {
                        val previous = pendingImport
                        pendingImport = null
                        val inspected = withContext(Dispatchers.IO) {
                            controller.discard(previous)
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                controller.cacheAndInspect(input)
                            } ?: error("Unable to open backup")
                    }
                    pendingImport = inspected
                    importAvailableSections = inspected.availableSections
                    importSections = inspected.availableSections
                } catch (e: Exception) {
                    Toast.makeText(context, resources.getString(R.string.toast_import_failed_fmt, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
                working = false
            }
        }
    }

    if (showExportSelection) {
        BackupSelectionDialog(
            title = stringResource(R.string.select_export_content),
            available = AppBackup.Section.entries.toSet(),
            selected = exportSections,
            showSensitiveWarning = exportSections.any { it in credentialBackupSections },
            confirmLabel = stringResource(R.string.export_backup_confirm),
            onSelectedChange = { exportSections = it },
            onDismiss = { showExportSelection = false },
            onConfirm = {
                pendingExportSections = exportSections
                showExportSelection = false
                exportLauncher.launch("st-app-backup.zip")
            },
        )
    }

    if (pendingImport != null) {
        BackupSelectionDialog(
            title = stringResource(R.string.select_import_content),
            available = importAvailableSections,
            selected = importSections,
            showSensitiveWarning = false,
            confirmLabel = stringResource(R.string.import_backup_confirm),
            confirmEnabled = destructiveActionsEnabled,
            onSelectedChange = { importSections = it },
            onDismiss = {
                controller.discard(pendingImport)
                pendingImport = null
            },
            onConfirm = {
                if (!destructiveActionsEnabled) return@BackupSelectionDialog
                val cached = pendingImport ?: return@BackupSelectionDialog
                pendingImport = null
                scope.launch {
                    working = true
                    try {
                        val result = withContext(Dispatchers.IO) {
                            controller.import(cached, importSections)
                        }
                        snapshot = withContext(Dispatchers.IO) { controller.snapshot() }
                        val message = if (result.files > 0 || result.sessions > 0) {
                            backupImportedMessage(result.files, result.sessions)
                        } else {
                            resources.getString(R.string.toast_backup_content_imported)
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            resources.getString(R.string.toast_import_failed_fmt, e.message ?: ""),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } finally {
                        controller.discard(cached)
                        working = false
                    }
                }
            },
        )
    }

    BackHandler(onBack = onBack)

    SettingsSubPage(stringResource(R.string.data_management), onBack) {
        SectionHeader(stringResource(R.string.storage_overview))
        OverviewCard(
            totalItems = snapshot.totalItems,
            totalSize = formatDataSize(snapshot.totalSizeBytes),
            apiCount = snapshot.apiCount,
        )

        SectionHeader(stringResource(R.string.category_management))
        categories.forEach { cat ->
            val info = snapshot.categories.find { it.key == cat.key }
            val count = info?.itemCount ?: 0
            CategoryCard(
                icon = cat.icon,
                label = cat.label(),
                count = count,
                size = formatDataSize(info?.sizeBytes ?: 0),
                fileCount = info?.fileCount ?: 0,
                onClear = { showClearCategory = cat },
                enabled = count > 0 && destructiveActionsEnabled,
            )
        }

        SectionHeader(stringResource(R.string.api_config))
        ApiConfigCard(
            providerCount = snapshot.apiCount,
            onReset = { showResetApi = true },
            enabled = destructiveActionsEnabled,
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        SectionHeader(stringResource(R.string.backup_restore))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space12),
        ) {
            OutlinedButton(
                onClick = {
                    importLauncher.launch(arrayOf(
                        "application/zip", "application/x-zip-compressed", "application/octet-stream"))
                },
                modifier = Modifier.weight(1f),
                enabled = !working && destructiveActionsEnabled,
            ) {
                Icon(Lucide.ArrowDownToLine, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Space8))
                Text(stringResource(R.string.import_backup))
            }
            OutlinedButton(
                onClick = { showExportSelection = true },
                modifier = Modifier.weight(1f),
                enabled = !working,
            ) {
                Icon(Lucide.ArrowUpToLine, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Space8))
                Text(stringResource(R.string.export_all))
            }
        }

        SectionHeader(stringResource(R.string.danger_zone))
        Button(
            onClick = { showClearAll = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !working && destructiveActionsEnabled && snapshot.totalItems > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(Lucide.Trash2, null, Modifier.size(18.dp))
            Spacer(Modifier.width(Space8))
            Text(stringResource(R.string.clear_all_data))
        }

        if (working) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.processing), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BackupSelectionDialog(
    title: String,
    available: Set<AppBackup.Section>,
    selected: Set<AppBackup.Section>,
    showSensitiveWarning: Boolean,
    confirmLabel: String,
    onSelectedChange: (Set<AppBackup.Section>) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space4),
            ) {
                available.sortedBy { it.ordinal }.forEach { section ->
                    val checked = section in selected
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                onSelectedChange(if (checked) selected - section else selected + section)
                            }
                            .padding(vertical = Space4),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { value ->
                                onSelectedChange(if (value) selected + section else selected - section)
                            },
                        )
                        Spacer(Modifier.width(Space8))
                        Text(stringResource(section.labelResId()), Modifier.weight(1f))
                    }
                }
                if (showSensitiveWarning) {
                    Text(
                        stringResource(R.string.backup_sensitive_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Space8),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = selected.isNotEmpty() && confirmEnabled) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun OverviewCard(totalItems: Int, totalSize: String, apiCount: Int) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Space16),
        verticalArrangement = Arrangement.spacedBy(Space12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space12)) {
            Icon(Lucide.Database, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.total_items_fmt, totalItems, totalItems),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space8),
        ) {
            StatChip(stringResource(R.string.items_unit), stringResource(R.string.items_count_fmt, totalItems), Modifier.weight(1f))
            StatChip(stringResource(R.string.space_unit), totalSize, Modifier.weight(1f))
            StatChip(stringResource(R.string.api_unit), stringResource(R.string.providers_count_fmt, apiCount), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = Space12, vertical = Space8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space4),
    ) {
        Text(value, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CategoryCard(
    icon: @Composable () -> Unit,
    label: String,
    count: Int,
    size: String,
    fileCount: Int,
    onClear: () -> Unit,
    enabled: Boolean,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Space16),
        verticalArrangement = Arrangement.spacedBy(Space12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space12)) {
            icon()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                Text(label, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(if (enabled) buildString {
                    append(androidx.compose.ui.res.pluralStringResource(R.plurals.items_with_count_fmt, count, count))
                    if (fileCount > 0) append(" · $size")
                } else stringResource(R.string.no_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (enabled) {
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Lucide.Trash2, null, Modifier.size(16.dp))
                Spacer(Modifier.width(Space4))
                Text("${stringResource(R.string.clear_all_prefix)}${label}")
            }
        }
    }
}

@Composable
private fun ApiConfigCard(
    providerCount: Int,
    onReset: () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Space16),
        verticalArrangement = Arrangement.spacedBy(Space12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space12)) {
            Icon(Lucide.Package, null, Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                Text(stringResource(R.string.api_config), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(androidx.compose.ui.res.pluralStringResource(R.plurals.providers_unit_fmt, providerCount, providerCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Lucide.Trash2, null, Modifier.size(16.dp))
            Spacer(Modifier.width(Space4))
            Text(stringResource(R.string.reset_to_default))
        }
    }
}
