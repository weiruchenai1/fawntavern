package me.rerere.fawntavern.ui.components

import android.net.Uri
import me.rerere.fawntavern.core.diagnostics.SafeLog
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.FilePlus
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import me.rerere.fawntavern.R
import me.rerere.fawntavern.core.resource.ImportableResourceController

private const val IMPORTABLE_LIST_TAG = "ImportableList"

internal data class ImportableLoadResult<T : Any>(
    val names: List<String>,
    val items: Map<String, T>,
    val failures: Map<String, Exception>,
)

internal suspend fun <T : Any> loadImportableItems(
    listNames: suspend () -> List<String>,
    loadItem: suspend (String) -> T,
): ImportableLoadResult<T> {
    val names = listNames()
    val items = mutableMapOf<String, T>()
    val failures = mutableMapOf<String, Exception>()
    names.forEach { name ->
        try {
            items[name] = loadItem(name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failures[name] = error
        }
    }
    return ImportableLoadResult(names, items, failures)
}

/**
 * 「新建条目」入口：传了它，FAB 就从「导入」变成「+」并弹出
 * [AddItemSheet]（命名新建 / 导入二选一）。
 */
data class CreateItemSpec(
    val titleRes: Int,
    val nameLabelRes: Int,
    val importLabelRes: Int,
    val createdToastRes: Int,
    val create: suspend (String) -> Unit,
)

/**
 * 可导入条目的通用列表页（预设/世界书）：顶栏 + 导入 FAB + 加载/空状态 +
 * 长按弹出重命名/删除菜单。数据操作全部由调用方以 suspend lambda 注入，
 * 本组件负责刷新时机与 Toast 提示。
 */
@Composable
fun <T : Any> ImportableListScreen(
    titleRes: Int,
    onBack: () -> Unit,
    importMimeType: String,
    emptyIcon: ImageVector,
    emptyTitleRes: Int,
    emptyDescRes: Int,
    renameLabelRes: Int,
    deleteTitleRes: Int,
    deleteMsgFmtRes: Int,
    controller: ImportableResourceController<T, Uri>,
    exportItem: (suspend (String) -> ByteArray)? = null,
    onOpen: (T) -> Unit,
    itemCard: @Composable (name: String, item: T, onClick: () -> Unit, onLongPress: () -> Unit) -> Unit,
    canRenameItem: (String) -> Boolean = { true },
    canDeleteItem: (String) -> Boolean = { true },
    createItem: CreateItemSpec? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val listNames = controller::names
    val loadItem = controller::load
    val importItem = controller::import
    val renameItem = controller::rename
    val deleteItem = controller::delete
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var items by remember { mutableStateOf<Map<String, T>>(emptyMap()) }
    var failedItems by remember { mutableStateOf<Map<String, Exception>>(emptyMap()) }
    var loadError by remember { mutableStateOf<Exception?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showAddSheet by remember { mutableStateOf(false) }

    var longPressName by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var exportTarget by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            loading = true
            loadError = null
            try {
                val result = loadImportableItems(listNames, loadItem)
                names = result.names
                items = result.items
                failedItems = result.failures
                result.failures.forEach { (name, error) ->
                    SafeLog.error(IMPORTABLE_LIST_TAG, "item_load_failed", error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SafeLog.error(IMPORTABLE_LIST_TAG, "item_list_load_failed", error)
                loadError = error
            } finally {
                loading = false
            }
        }
    }

    fun retryItem(name: String) {
        scope.launch {
            try {
                val item = loadItem(name)
                items = items + (name to item)
                failedItems = failedItems - name
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SafeLog.error(IMPORTABLE_LIST_TAG, "item_reload_failed", error)
                failedItems = failedItems + (name to error)
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            showAddSheet = false
            scope.launch {
                var imported = 0
                var failed = 0
                for (uri in uris) {
                    try {
                        importItem(uri)
                        imported++
                    } catch (_: Exception) {
                        failed++
                    }
                }
                if (imported > 0) {
                    Toast.makeText(context, resources.getQuantityString(R.plurals.toast_imported_files_fmt, imported, imported), Toast.LENGTH_SHORT).show()
                }
                if (failed > 0) {
                    Toast.makeText(context, resources.getQuantityString(R.plurals.toast_import_failed_count_fmt, failed, failed), Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
        }
    }

    if (showAddSheet && createItem != null) {
        AddItemSheet(
            title = stringResource(createItem.titleRes),
            nameLabel = stringResource(createItem.nameLabelRes),
            importLabel = stringResource(createItem.importLabelRes),
            onImport = { importLauncher.launch(importMimeType) },
            onCreate = { name ->
                scope.launch {
                    try {
                        createItem.create(name)
                        showAddSheet = false
                        Toast.makeText(context, resources.getString(createItem.createdToastRes), Toast.LENGTH_SHORT).show()
                        refresh()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        SafeLog.error(IMPORTABLE_LIST_TAG, "item_create_failed", error)
                        Toast.makeText(
                            context,
                            resources.getString(R.string.create_failed_fmt, error.message.orEmpty()),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onDismiss = { showAddSheet = false },
        )
    }

    fun writeExport(uri: Uri?) {
        if (uri == null) return
        val exporter = exportItem ?: return
        val target = exportTarget
        scope.launch {
            try {
                val bytes = exporter(target)
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Unable to open the selected destination")
                    output.use { it.write(bytes) }
                }
                Toast.makeText(context, resources.getString(R.string.toast_export_success), Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    resources.getString(R.string.toast_export_failed_fmt, error.message.orEmpty()),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
        ::writeExport,
    )

    showRenameDialog?.let { oldName ->
        RenameDialog(
            initialName = oldName,
            label = stringResource(renameLabelRes),
            onConfirm = { newName ->
                scope.launch {
                    if (renameItem(oldName, newName)) {
                        Toast.makeText(context, resources.getString(R.string.toast_renamed), Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
                showRenameDialog = null
            },
            onDismiss = { showRenameDialog = null },
        )
    }

    showDeleteDialog?.let { name ->
        ConfirmDeleteDialog(
            title = stringResource(deleteTitleRes),
            text = stringResource(deleteMsgFmtRes, name),
            onConfirm = {
                scope.launch {
                    deleteItem(name)
                    Toast.makeText(context, resources.getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                    refresh()
                }
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null },
        )
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(stringResource(titleRes), onBack, actions) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (createItem != null) showAddSheet = true else importLauncher.launch(importMimeType)
                }
            ) {
                Icon(
                    if (createItem != null) Lucide.Plus else Lucide.FilePlus,
                    stringResource(createItem?.titleRes ?: R.string.import_label),
                    Modifier.size(24.dp),
                )
            }
        }
    ) { padding ->
        if (loading) {
            LoadingState(Modifier.padding(padding))
        } else if (loadError != null) {
            ErrorState(
                icon = Lucide.TriangleAlert,
                message = resources.getString(R.string.list_load_failed_fmt, loadError?.message.orEmpty()),
                onRetry = ::refresh,
                modifier = Modifier.padding(padding),
            )
        } else if (names.isEmpty()) {
            EmptyState(emptyIcon, stringResource(emptyTitleRes),
                stringResource(emptyDescRes), Modifier.padding(padding))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(Space12)
            ) {
                items(names, key = { it }) { name ->
                    val item = items[name]
                    val failure = failedItems[name]
                    Box {
                        if (item != null) {
                            itemCard(name, item,
                                { onOpen(item) },
                                { if (canRenameItem(name) || exportItem != null || canDeleteItem(name)) longPressName = name })
                        } else {
                            FailedImportableItem(
                                name = name,
                                detail = failure?.message,
                                onRetry = { retryItem(name) },
                                onLongPress = { if (canRenameItem(name) || canDeleteItem(name)) longPressName = name },
                            )
                        }
                        DropdownMenu(
                            expanded = longPressName == name,
                            onDismissRequest = { longPressName = null },
                        ) {
                            if (canRenameItem(name)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename)) },
                                    leadingIcon = { Icon(Lucide.Pencil, null, Modifier.size(18.dp)) },
                                    onClick = { longPressName = null; showRenameDialog = name })
                            }
                            if (exportItem != null && item != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_json)) },
                                    leadingIcon = { Icon(Lucide.FileJson, null, Modifier.size(18.dp)) },
                                    onClick = {
                                        longPressName = null
                                        exportTarget = name
                                        exportJsonLauncher.launch("$name.json")
                                    },
                                )
                            }
                            if (canDeleteItem(name)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Lucide.Trash2, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                                    onClick = { longPressName = null; showDeleteDialog = name })
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun FailedImportableItem(
    name: String,
    detail: String?,
    onRetry: () -> Unit,
    onLongPress: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().appClickable(onClick = onRetry, onLongClick = onLongPress),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space12, vertical = Space12),
            horizontalArrangement = Arrangement.spacedBy(Space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.TriangleAlert, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    detail?.takeIf { it.isNotBlank() } ?: stringResource(R.string.item_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppIconButton(
                icon = Lucide.RefreshCw,
                contentDescription = stringResource(R.string.retry),
                onClick = onRetry,
                size = 36.dp,
                iconSize = 18.dp,
            )
        }
    }
}
