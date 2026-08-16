package me.rerere.fawntavern.ui.character

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FilePlus
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Smile
import com.composables.icons.lucide.Trash2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.character.CharacterCard
import sh.calvin.reorderable.ReorderableItem
import me.rerere.fawntavern.ui.components.rememberReorderableList
import me.rerere.fawntavern.ui.components.AppTopBar
import me.rerere.fawntavern.ui.components.appClickable
import me.rerere.fawntavern.ui.components.draggableLiftScale
import me.rerere.fawntavern.ui.components.ConfirmDeleteDialog
import me.rerere.fawntavern.ui.components.EmptyState
import me.rerere.fawntavern.ui.components.LoadingState
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterListScreen(onBack: () -> Unit, onSelect: (CharacterCard) -> Unit = {}) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val controller = remember(context) {
        CharacterLibraryController(AndroidCharacterLibraryDataSource(context))
    }
    val orderSaveCoordinator = remember(controller, scope) {
        CharacterOrderSaveCoordinator(
            scope = scope,
            save = controller::saveOrder,
            onFailure = { error ->
                Toast.makeText(
                    context,
                    resources.getString(R.string.char_order_save_failed_fmt, error.message.orEmpty()),
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var chars by remember { mutableStateOf<Map<String, CharacterCard>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var selectedChar by remember { mutableStateOf<CharacterCard?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var newCharacterName by remember { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportName by remember { mutableStateOf("") }

    var longPressName by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    // 长按导出时记录目标卡名，SAF 选好保存位置后在回调里取用
    var exportTarget by remember { mutableStateOf("") }
    // 图片版本号：编辑器改了角色卡图片后 +1，强制列表缩略图重新解码
    var imageVersion by remember { mutableIntStateOf(0) }
    // 内置默认角色卡不可删除：它是角色选择面板与主界面的兜底
    val defaultCardName = remember(controller) { controller.defaultCardName() }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                val loaded = controller.load()
                names = loaded.names
                chars = loaded.cards
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    resources.getString(R.string.list_load_failed_fmt, error.message.orEmpty()),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingImportUri = uri
        pendingImportName = uri?.lastPathSegment?.substringAfterLast('/') ?: uri?.toString().orEmpty()
    }

    fun dismissAddSheet() {
        showAddSheet = false
        newCharacterName = ""
        pendingImportUri = null
        pendingImportName = ""
    }

    fun saveNewCharacter() {
        val uri = pendingImportUri
        val name = newCharacterName.trim()
        if (uri == null && name.isBlank()) return
        scope.launch {
            try {
                if (uri != null) {
                    controller.import(uri)
                    Toast.makeText(context, resources.getString(R.string.character_imported), Toast.LENGTH_SHORT).show()
                } else {
                    controller.create(name)
                    Toast.makeText(context, resources.getString(R.string.character_created), Toast.LENGTH_SHORT).show()
                }
                dismissAddSheet()
                refresh()
            } catch (error: Exception) {
                Toast.makeText(context, resources.getString(R.string.char_save_failed_fmt, error.message.orEmpty()), Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(onDismissRequest = ::dismissAddSheet) {
            Column(
                Modifier.fillMaxWidth().imePadding().padding(horizontal = Space16, bottom = Space16),
                verticalArrangement = Arrangement.spacedBy(Space12),
            ) {
                Text(stringResource(R.string.add_character), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = newCharacterName,
                    onValueChange = { newCharacterName = it },
                    label = { Text(stringResource(R.string.char_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { importLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Lucide.FilePlus, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Space8))
                    Text(stringResource(R.string.import_character_card))
                }
                if (pendingImportUri != null) {
                    Text(
                        stringResource(R.string.selected_file_fmt, pendingImportName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = ::dismissAddSheet, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = ::saveNewCharacter,
                        enabled = pendingImportUri != null || newCharacterName.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }

    // 导出启动器（SAF 另存为）
    fun writeExport(uri: Uri?, bytes: suspend () -> ByteArray) {
        if (uri == null) return
        scope.launch {
            try {
                val data = bytes()
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Unable to open the selected destination")
                    output.use { it.write(data) }
                }
                Toast.makeText(context, resources.getString(R.string.toast_export_success), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, resources.getString(R.string.toast_export_failed_fmt, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val exportPngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri -> writeExport(uri) { controller.exportPng(exportTarget) } }
    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> writeExport(uri) { controller.exportJson(exportTarget) } }

    showDeleteDialog?.let { name ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.delete_character_title),
            text = stringResource(R.string.delete_character_msg_fmt, name),
            onConfirm = {
                scope.launch {
                    try {
                        controller.delete(name)
                        Toast.makeText(context, resources.getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                        refresh()
                    } catch (error: Exception) {
                        Toast.makeText(
                            context,
                            resources.getString(R.string.delete_failed_fmt, error.message.orEmpty()),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null },
        )
    }

    // 保存世界书关联时需要用到卡片文件名
    var editingFileName by remember { mutableStateOf("") }
    // SaveableStateHolder：进入编辑器时列表离开组合，其 LazyListState 被暂存；
    // 返回时恢复，避免列表滚动位置丢失（跳回顶部）。
    val stateHolder = rememberSaveableStateHolder()
    if (selectedChar != null) {
        // 编辑器里的 TextFieldState 只属于当前这次编辑。不要放进固定 key 的
        // SaveableStateProvider，否则打开另一张卡时会恢复上一张卡的角色定义。
        BackHandler { selectedChar = null; imageVersion++; refresh() }
        CharacterEditorScreen(
            card = selectedChar!!,
            onBack = { selectedChar = null; imageVersion++; refresh() },
            cardFileName = editingFileName,
        )
        return
    }

    stateHolder.SaveableStateProvider("list") {
        BackHandler(onBack = onBack)

        Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(stringResource(R.string.characters), onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Lucide.Plus, stringResource(R.string.add_character), Modifier.size(24.dp))
            }
        }
    ) { padding ->
        if (loading) {
            LoadingState(Modifier.padding(padding))
        } else if (names.isEmpty()) {
            EmptyState(Lucide.FileJson, stringResource(R.string.no_characters_title),
                stringResource(R.string.no_characters_desc), Modifier.padding(padding))
        } else {
            // 长按 grip 手柄拖动排序，靠近边缘自动滚动（sh.calvin.reorderable）
            val (listState, reorderState) = rememberReorderableList(
                items = names,
                keyOf = { it },
            ) { list ->
                names = list
                orderSaveCoordinator.request(list)
            }

            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(Space12)
            ) {
                itemsIndexed(names, key = { _, n -> n }) { _, name ->
                    val c = chars[name] ?: return@itemsIndexed
                    ReorderableItem(reorderState, key = name) { dragging ->
                        Box {
                            CharCard(
                                c = c,
                                imageKey = imageVersion,
                                imageFile = controller.imageFile(name),
                                onClick = { selectedChar = c; editingFileName = name },
                                onLongPress = { longPressName = name },
                                dragging = dragging,
                                modifier = Modifier.longPressDraggableHandle(),
                            )
                        DropdownMenu(
                            expanded = longPressName == name,
                            onDismissRequest = { longPressName = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_png)) },
                                leadingIcon = { Icon(Lucide.Image, null, Modifier.size(18.dp)) },
                                onClick = {
                                    longPressName = null
                                    exportTarget = name
                                    exportPngLauncher.launch("$name.png")
                                })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_json)) },
                                leadingIcon = { Icon(Lucide.FileJson, null, Modifier.size(18.dp)) },
                                onClick = {
                                    longPressName = null
                                    exportTarget = name
                                    exportJsonLauncher.launch("$name.json")
                                })
                            // 内置默认角色卡不提供删除
                            if (name != defaultCardName) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Lucide.Trash2, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                                    onClick = { longPressName = null; showDeleteDialog = name })
                            }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
    } // SaveableStateProvider("list")
}

@Composable
private fun CharCard(
    c: CharacterCard,
    imageKey: Int,
    imageFile: java.io.File,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
) {
    // imageKey 变化即重新解码（编辑器换图后刷新）；文件不存在则为 null，回退到笑脸占位
    val bitmap = remember(imageFile.path, imageKey) {
        if (imageFile.exists()) {
            try { BitmapFactory.decodeFile(imageFile.path) } catch (_: Exception) { null }
        } else null
    }
    Row(
        Modifier.fillMaxWidth()
            .draggableLiftScale(dragging)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(end = Space16),
        horizontalArrangement = Arrangement.spacedBy(Space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 点按/长按只在内容区生效：长按 grip 只触发拖拽，不会同时弹出导出/删除菜单
        Row(
            Modifier.weight(1f)
                .appClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(start = Space16, top = Space16, bottom = Space16),
            horizontalArrangement = Arrangement.spacedBy(Space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Lucide.Smile, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space8)) {
                Text(c.name, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)

                if (c.tags.isNotEmpty()) {
                    Text(c.tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (c.firstMes.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space4)) {
                        Icon(Lucide.MessageCircle, null, Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(c.firstMes.take(40).replace("\n", " "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // 拖动手柄：长按后上下拖拽排序
        Icon(
            Lucide.GripVertical, stringResource(R.string.reorder),
            Modifier.size(24.dp).then(modifier),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
