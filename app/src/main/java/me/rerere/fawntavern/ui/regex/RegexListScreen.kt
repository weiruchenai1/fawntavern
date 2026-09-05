package me.rerere.fawntavern.ui.regex

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.FilePlus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.di.LocalAppContainer
import me.rerere.fawntavern.data.preset.RegexScript
import me.rerere.fawntavern.ui.components.AddItemSheet
import me.rerere.fawntavern.ui.components.AppTopBar
import me.rerere.fawntavern.ui.components.ConfirmDeleteDialog
import me.rerere.fawntavern.ui.components.EmptyState
import me.rerere.fawntavern.ui.components.RenameDialog
import me.rerere.fawntavern.ui.components.appClickable
import me.rerere.fawntavern.ui.preset.RegexEditDialog
import java.io.IOException

private data class RegexEditTarget(val source: RegexSource, val index: Int, val script: RegexScript)
private data class RegexDeleteTarget(val source: RegexSource, val index: Int, val name: String)

@Composable
fun RegexListScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    val controller = LocalAppContainer.current.features.regex
    val pagerState = rememberPagerState(pageCount = { 3 })
    var globalGroups by remember { mutableStateOf<List<RegexGroup>>(emptyList()) }
    var presetGroups by remember { mutableStateOf<List<RegexGroup>>(emptyList()) }
    var localGroups by remember { mutableStateOf<List<RegexGroup>>(emptyList()) }
    var selectedSource by remember { mutableStateOf<RegexSource?>(null) }
    var editing by remember { mutableStateOf<RegexEditTarget?>(null) }
    var deleting by remember { mutableStateOf<RegexDeleteTarget?>(null) }
    var addTarget by remember { mutableStateOf<RegexSource?>(null) }
    var importTarget by remember { mutableStateOf<RegexSource?>(null) }
    var longPressSource by remember { mutableStateOf<RegexSource?>(null) }
    var longPressRegex by remember { mutableStateOf<RegexEditTarget?>(null) }
    var renameSource by remember { mutableStateOf<RegexSource?>(null) }
    var deleteSource by remember { mutableStateOf<RegexSource?>(null) }
    var exportScript by remember { mutableStateOf<RegexScript?>(null) }

    fun groups(scope: RegexScope): List<RegexGroup> = when (scope) {
        RegexScope.GLOBAL -> globalGroups
        RegexScope.PRESET -> presetGroups
        RegexScope.LOCAL -> localGroups
    }

    fun scripts(source: RegexSource): List<RegexScript> = when (source.scope) {
        RegexScope.GLOBAL -> globalGroups.firstOrNull { it.name == source.name }?.scripts.orEmpty()
        RegexScope.PRESET -> presetGroups.firstOrNull { it.name == source.name }?.scripts.orEmpty()
        RegexScope.LOCAL -> localGroups.firstOrNull { it.name == source.name }?.scripts.orEmpty()
    }

    fun updateGroup(source: RegexSource, updated: List<RegexScript>) {
        when (source.scope) {
            RegexScope.GLOBAL -> globalGroups = globalGroups.map {
                if (it.name == source.name) it.copy(scripts = updated) else it
            }
            RegexScope.PRESET -> presetGroups = presetGroups.map { if (it.name == source.name) it.copy(scripts = updated) else it }
            RegexScope.LOCAL -> localGroups = localGroups.map {
                if (it.name == source.name) it.copy(scripts = updated) else it
            }
        }
    }

    suspend fun reloadGroups() {
        val catalog = controller.load()
        globalGroups = catalog.global
        presetGroups = catalog.preset
        localGroups = catalog.local
    }

    fun appendScripts(source: RegexSource, additions: List<RegexScript>) {
        if (additions.isEmpty()) return
        val updated = scripts(source) + additions
        updateGroup(source, updated)
        coroutineScope.launch {
            controller.append(source, additions)
        }
    }

    fun appendScript(source: RegexSource, script: RegexScript) {
        appendScripts(source, listOf(script))
    }

    fun saveScript(target: RegexEditTarget, script: RegexScript) {
        if (target.index < 0) {
            appendScript(target.source, script)
            return
        }
        val updated = scripts(target.source).toMutableList()
        if (target.index !in updated.indices) return
        updated[target.index] = script
        updateGroup(target.source, updated)
        coroutineScope.launch {
            controller.update(target.source, target.index, script, updated)
        }
    }

    fun deleteScript(target: RegexDeleteTarget) {
        val updated = scripts(target.source).toMutableList()
        if (target.index !in updated.indices) return
        updated.removeAt(target.index)
        updateGroup(target.source, updated)
        coroutineScope.launch {
            controller.deleteScript(target.source, target.index, updated)
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val target = importTarget
        importTarget = null
        if (uris.isNotEmpty() && target != null) coroutineScope.launch {
            val additions = mutableListOf<RegexScript>()
            var failed = 0
            for (uri in uris) {
                val script = runCatching { controller.importScript(uri) }.getOrNull()
                if (script != null) {
                    additions += script
                } else {
                    failed++
                }
            }
            appendScripts(target, additions)
            val imported = additions.size
            if (imported > 0) Toast.makeText(
                context,
                resources.getQuantityString(R.plurals.toast_imported_files_fmt, imported, imported),
                Toast.LENGTH_SHORT,
            ).show()
            if (failed > 0) Toast.makeText(
                context,
                resources.getQuantityString(R.plurals.toast_import_failed_count_fmt, failed, failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun writeExport(uri: Uri?) {
        val script = exportScript
        exportScript = null
        if (uri == null || script == null) return
        coroutineScope.launch {
            try {
                val bytes = controller.serialize(script)
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

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
        ::writeExport,
    )

    LaunchedEffect(Unit) {
        reloadGroups()
    }

    val selected = selectedSource
    if (selected != null) {
        val closeDetail = {
            longPressRegex = null
            selectedSource = null
        }
        BackHandler { closeDetail() }
        RegexSourceDetail(
            title = groups(selected.scope).firstOrNull { it.name == selected.name }?.displayName ?: selected.name,
            scripts = scripts(selected),
            onBack = closeDetail,
            onAdd = { addTarget = selected },
            onEdit = { index, script -> editing = RegexEditTarget(selected, index, script) },
            longPressTarget = longPressRegex,
            onLongPress = { index, script ->
                longPressRegex = RegexEditTarget(selected, index, script)
            },
            onDismissMenu = { longPressRegex = null },
            onExport = { script ->
                longPressRegex = null
                exportScript = script
                exportLauncher.launch("${script.exportFileName()}.json")
            },
            onToggle = { index, script -> saveScript(RegexEditTarget(selected, index, script), script.copy(disabled = !script.disabled)) },
            onDelete = { index, script -> deleting = RegexDeleteTarget(selected, index, script.scriptName) },
        )
    } else {
        BackHandler(onBack = onBack)
        val titles = listOf(
            stringResource(R.string.regex_global),
            stringResource(R.string.regex_preset),
            stringResource(R.string.regex_local),
        )
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    AppTopBar(stringResource(R.string.regex_title), onBack)
                    PrimaryScrollableTabRow(selectedTabIndex = pagerState.currentPage, edgePadding = 16.dp) {
                        titles.forEachIndexed { index, title ->
                            Tab(
                                selected = index == pagerState.currentPage,
                                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                            ) {
                                Text(title, Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    when (pagerState.currentPage) {
                        0 -> addTarget = RegexSource(RegexScope.GLOBAL)
                        1 -> addTarget = RegexSource(RegexScope.PRESET)
                        else -> addTarget = RegexSource(RegexScope.LOCAL)
                    }
                }) {
                    Icon(
                        Lucide.Plus,
                        contentDescription = stringResource(
                            if (pagerState.currentPage == 0) R.string.add_regex else R.string.add_regex_group,
                        ),
                    )
                }
            },
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().padding(padding),
                key = { it },
                overscrollEffect = null,
            ) { page ->
                when (page) {
                    0 -> RegexGroupsList(
                        scope = RegexScope.GLOBAL,
                        groups = globalGroups,
                        onOpen = { selectedSource = RegexSource(RegexScope.GLOBAL, it) },
                        onLongPress = { longPressSource = RegexSource(RegexScope.GLOBAL, it) },
                        longPressSource = longPressSource,
                        onDismissMenu = { longPressSource = null },
                        onRename = { renameSource = it },
                        onDelete = { deleteSource = it },
                    )
                    1 -> RegexGroupsList(
                        scope = RegexScope.PRESET,
                        groups = presetGroups,
                        onOpen = { selectedSource = RegexSource(RegexScope.PRESET, it) },
                        onLongPress = { longPressSource = RegexSource(RegexScope.PRESET, it) },
                        longPressSource = longPressSource,
                        onDismissMenu = { longPressSource = null },
                        onRename = { renameSource = it },
                        onDelete = { deleteSource = it },
                        canDelete = { it != controller.defaultPresetName() },
                    )
                    else -> RegexGroupsList(
                        scope = RegexScope.LOCAL,
                        groups = localGroups,
                        onOpen = { selectedSource = RegexSource(RegexScope.LOCAL, it) },
                        onLongPress = { longPressSource = RegexSource(RegexScope.LOCAL, it) },
                        longPressSource = longPressSource,
                        onDismissMenu = { longPressSource = null },
                        onRename = { renameSource = it },
                        onDelete = { deleteSource = it },
                    )
                }
            }
        }
    }

    addTarget?.let { target ->
        if (target.name.isBlank()) {
            AddItemSheet(
                title = stringResource(R.string.add_regex_group),
                nameLabel = stringResource(R.string.regex_group_name),
                onCreate = { requestedName ->
                    coroutineScope.launch {
                        try {
                            controller.create(target, requestedName)
                            reloadGroups()
                            addTarget = null
                        } catch (error: Exception) {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.create_failed_fmt, error.message.orEmpty()),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
                onDismiss = { addTarget = null },
            )
        } else {
            RegexAddSheet(
                onCreate = {
                    addTarget = null
                    editing = RegexEditTarget(
                        source = target,
                        index = -1,
                        script = RegexScript(
                            id = java.util.UUID.randomUUID().toString(),
                            scriptName = resources.getString(R.string.unnamed_prompt),
                        ),
                    )
                },
                onImport = {
                    addTarget = null
                    importTarget = target
                    importer.launch("application/json")
                },
                onDismiss = { addTarget = null },
            )
        }
    }

    renameSource?.let { target ->
        val group = groups(target.scope).firstOrNull { it.name == target.name }
        RenameDialog(
            initialName = group?.displayName ?: target.name,
            label = stringResource(when (target.scope) {
                RegexScope.GLOBAL -> R.string.regex_group_name
                RegexScope.PRESET -> R.string.toast_rename_preset_label
                RegexScope.LOCAL -> R.string.regex_group_name
            }),
            onConfirm = { newName ->
                coroutineScope.launch {
                    val renamed = controller.rename(target, newName)
                    if (renamed) {
                        reloadGroups()
                        Toast.makeText(context, resources.getString(R.string.toast_renamed), Toast.LENGTH_SHORT).show()
                    }
                }
                renameSource = null
            },
            onDismiss = { renameSource = null },
        )
    }

    deleteSource?.let { target ->
        val displayName = groups(target.scope).firstOrNull { it.name == target.name }?.displayName ?: target.name
        ConfirmDeleteDialog(
            title = stringResource(when (target.scope) {
                RegexScope.GLOBAL -> R.string.delete_regex_group_title
                RegexScope.PRESET -> R.string.delete_preset_title
                RegexScope.LOCAL -> R.string.delete_regex_group_title
            }),
            text = stringResource(when (target.scope) {
                RegexScope.GLOBAL -> R.string.delete_regex_group_msg_fmt
                RegexScope.PRESET -> R.string.delete_preset_msg_fmt
                RegexScope.LOCAL -> R.string.delete_regex_group_msg_fmt
            },
                displayName,
            ),
            onConfirm = {
                coroutineScope.launch {
                    controller.delete(target)
                    reloadGroups()
                    Toast.makeText(context, resources.getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                }
                deleteSource = null
            },
            onDismiss = { deleteSource = null },
        )
    }

    editing?.let { target ->
        RegexEditDialog(
            script = target.script,
            onDismiss = { editing = null },
            onSave = {
                saveScript(target, it)
                editing = null
            },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_regex_title)) },
            text = { Text(target.name.ifBlank { stringResource(R.string.unnamed_prompt) }) },
            confirmButton = {
                TextButton(onClick = {
                    deleteScript(target)
                    deleting = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegexAddSheet(
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.add_regex), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Icon(Lucide.Plus, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.add_regex))
            }
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Lucide.FilePlus, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.import_regex))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun RegexSourceDetail(
    title: String,
    scripts: List<RegexScript>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int, RegexScript) -> Unit,
    longPressTarget: RegexEditTarget?,
    onLongPress: (Int, RegexScript) -> Unit,
    onDismissMenu: () -> Unit,
    onExport: (RegexScript) -> Unit,
    onToggle: (Int, RegexScript) -> Unit,
    onDelete: (Int, RegexScript) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title, onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Lucide.Plus, contentDescription = stringResource(R.string.add_regex))
            }
        },
    ) { padding ->
        RegexScriptsList(
            scripts = scripts,
            onEdit = onEdit,
            longPressTarget = longPressTarget,
            onLongPress = onLongPress,
            onDismissMenu = onDismissMenu,
            onExport = onExport,
            onToggle = onToggle,
            onDelete = onDelete,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun RegexScriptsList(
    scripts: List<RegexScript>,
    onEdit: (Int, RegexScript) -> Unit,
    longPressTarget: RegexEditTarget?,
    onLongPress: (Int, RegexScript) -> Unit,
    onDismissMenu: () -> Unit,
    onExport: (RegexScript) -> Unit,
    onToggle: (Int, RegexScript) -> Unit,
    onDelete: (Int, RegexScript) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (scripts.isEmpty()) {
        EmptyState(
            icon = Lucide.FileJson,
            title = stringResource(R.string.regex_empty),
            desc = stringResource(R.string.regex_empty_desc),
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.size(8.dp)) }
        itemsIndexed(scripts, key = { index, script -> "${script.id}#$index" }) { index, script ->
            Box {
                RegexRow(
                    script = script,
                    onEdit = { onEdit(index, script) },
                    onLongPress = { onLongPress(index, script) },
                    onToggle = { onToggle(index, script) },
                    onDelete = { onDelete(index, script) },
                )
                DropdownMenu(
                    expanded = longPressTarget?.index == index,
                    onDismissRequest = onDismissMenu,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_json)) },
                        leadingIcon = { Icon(Lucide.FileJson, null, Modifier.size(18.dp)) },
                        onClick = { onExport(script) },
                    )
                }
            }
        }
        item { Spacer(Modifier.size(80.dp)) }
    }
}

@Composable
private fun RegexGroupsList(
    scope: RegexScope,
    groups: List<RegexGroup>,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
    longPressSource: RegexSource?,
    onDismissMenu: () -> Unit,
    onRename: (RegexSource) -> Unit,
    onDelete: (RegexSource) -> Unit,
    canDelete: (String) -> Boolean = { true },
) {
    if (groups.isEmpty()) {
        EmptyState(
            icon = Lucide.FileJson,
            title = stringResource(R.string.regex_group_empty),
            desc = stringResource(R.string.regex_group_empty_desc),
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.size(8.dp)) }
        items(groups, key = { it.name }) { group ->
            Box {
                RegexGroupCard(
                    group,
                    onClick = { onOpen(group.name) },
                    onLongPress = { onLongPress(group.name) },
                )
                val source = RegexSource(scope, group.name)
                DropdownMenu(
                    expanded = longPressSource == source,
                    onDismissRequest = onDismissMenu,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        leadingIcon = { Icon(Lucide.Pencil, null, Modifier.size(18.dp)) },
                        onClick = { onDismissMenu(); onRename(source) },
                    )
                    if (canDelete(group.name)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(Lucide.Trash2, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = { onDismissMenu(); onDelete(source) },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.size(80.dp)) }
    }
}

@Composable
private fun RegexGroupCard(group: RegexGroup, onClick: () -> Unit, onLongPress: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .appClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                group.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.regex_count_fmt, group.scripts.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Lucide.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RegexRow(
    script: RegexScript,
    onEdit: () -> Unit,
    onLongPress: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val enabled = !script.disabled
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .appClickable(onClick = onEdit, onLongClick = onLongPress)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                script.scriptName.ifBlank { stringResource(R.string.unnamed_prompt) },
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${script.findRegex.take(36)} -> ${script.replaceString.take(36)}".replace("\n", " "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = enabled, onCheckedChange = { onToggle() })
        androidx.compose.material3.IconButton(onClick = onDelete) {
            Icon(Lucide.Trash2, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
        }
    }
}

private fun RegexScript.exportFileName(): String = scriptName
    .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
    .trim()
    .trim('.')
    .ifBlank { id.ifBlank { "regex" } }
