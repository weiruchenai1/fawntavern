package me.rerere.fawntavern.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.FilePlus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Trash2
import kotlinx.coroutines.launch
import me.rerere.fawntavern.R

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
    listNames: suspend () -> List<String>,
    loadItem: suspend (String) -> T,
    importItem: suspend (Uri) -> String,
    renameItem: suspend (oldName: String, newName: String) -> Boolean,
    deleteItem: suspend (String) -> Unit,
    onOpen: (T) -> Unit,
    itemCard: @Composable (name: String, item: T, onClick: () -> Unit, onLongPress: () -> Unit) -> Unit,
    actions: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var items by remember { mutableStateOf<Map<String, T>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    var longPressName by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            names = listNames()
            val map = mutableMapOf<String, T>()
            for (n in names) {
                try { map[n] = loadItem(n) } catch (_: Exception) {}
            }
            items = map
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val name = importItem(uri)
                    Toast.makeText(context, context.getString(R.string.toast_imported_fmt, name), Toast.LENGTH_SHORT).show()
                    refresh()
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.toast_import_failed_fmt, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    showRenameDialog?.let { oldName ->
        RenameDialog(
            initialName = oldName,
            label = stringResource(renameLabelRes),
            onConfirm = { newName ->
                scope.launch {
                    if (renameItem(oldName, newName)) {
                        Toast.makeText(context, context.getString(R.string.toast_renamed), Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, context.getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                    refresh()
                }
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(stringResource(titleRes), onBack, actions) },
        floatingActionButton = {
            FloatingActionButton(onClick = { importLauncher.launch(importMimeType) }) {
                Icon(Lucide.FilePlus, stringResource(R.string.import_label), Modifier.size(24.dp))
            }
        }
    ) { padding ->
        if (loading) {
            LoadingState(Modifier.padding(padding))
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
                    val item = items[name] ?: return@items
                    Box {
                        itemCard(name, item,
                            { onOpen(item) },
                            { longPressName = name })
                        DropdownMenu(
                            expanded = longPressName == name,
                            onDismissRequest = { longPressName = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                leadingIcon = { Icon(Lucide.Pencil, null, Modifier.size(18.dp)) },
                                onClick = { longPressName = null; showRenameDialog = name })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Lucide.Trash2, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                                onClick = { longPressName = null; showDeleteDialog = name })
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}
