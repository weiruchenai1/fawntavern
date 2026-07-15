package me.rerere.stapp.ui.character

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FilePlus
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Smile
import com.composables.icons.lucide.Trash2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.stapp.R
import me.rerere.stapp.data.character.CharacterCard
import me.rerere.stapp.data.character.CharacterRepository
import me.rerere.stapp.ui.components.AppTopBar
import me.rerere.stapp.ui.components.ConfirmDeleteDialog
import me.rerere.stapp.ui.components.EmptyState
import me.rerere.stapp.ui.components.LoadingState
import me.rerere.stapp.ui.components.Space4
import me.rerere.stapp.ui.components.Space8
import me.rerere.stapp.ui.components.Space12
import me.rerere.stapp.ui.components.Space16

@Composable
fun CharacterListScreen(onBack: () -> Unit, onSelect: (CharacterCard) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var chars by remember { mutableStateOf<Map<String, CharacterCard>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var selectedChar by remember { mutableStateOf<CharacterCard?>(null) }

    var longPressName by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    // 长按导出时记录目标卡名，SAF 选好保存位置后在回调里取用
    var exportTarget by remember { mutableStateOf("") }
    // 图片版本号：编辑器改了角色卡图片后 +1，强制列表缩略图重新解码
    var imageVersion by remember { mutableStateOf(0) }
    // 内置默认角色卡不可删除：它是角色选择面板与主界面的兜底
    val defaultCardName = remember { CharacterRepository.defaultCardName(context) }

    fun refresh() {
        scope.launch {
            loading = true
            names = CharacterRepository.listNames(context)
            val map = mutableMapOf<String, CharacterCard>()
            for (n in names) {
                try { map[n] = CharacterRepository.load(context, n) } catch (_: Exception) {}
            }
            chars = map
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
                    val card = CharacterRepository.import(context, uri)
                    Toast.makeText(context, context.getString(R.string.toast_imported_fmt, card.name), Toast.LENGTH_SHORT).show()
                    refresh()
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.toast_import_failed_fmt, e.message ?: ""), Toast.LENGTH_SHORT).show()
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
                    context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                }
                Toast.makeText(context, context.getString(R.string.toast_export_success), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.toast_export_failed_fmt, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val exportPngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri -> writeExport(uri) { CharacterRepository.exportPngBytes(context, exportTarget) } }
    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> writeExport(uri) { CharacterRepository.exportJsonBytes(context, exportTarget) } }

    showDeleteDialog?.let { name ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.delete_character_title),
            text = stringResource(R.string.delete_character_msg_fmt, name),
            onConfirm = {
                scope.launch {
                    CharacterRepository.delete(context, name)
                    Toast.makeText(context, context.getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                    refresh()
                }
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null },
        )
    }

    // 保存世界书关联时需要用到卡片文件名
    var editingFileName by remember { mutableStateOf("") }
    if (selectedChar != null) {
        BackHandler { selectedChar = null; imageVersion++; refresh() }
        CharacterEditorScreen(
            card = selectedChar!!,
            onBack = { selectedChar = null; imageVersion++; refresh() },
            cardFileName = editingFileName,
        )
        return
    }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(stringResource(R.string.characters), onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { importLauncher.launch("*/*") }) {
                Icon(Lucide.FilePlus, stringResource(R.string.import_label), Modifier.size(24.dp))
            }
        }
    ) { padding ->
        if (loading) {
            LoadingState(Modifier.padding(padding))
        } else if (names.isEmpty()) {
            EmptyState(Lucide.FileJson, stringResource(R.string.no_characters_title),
                stringResource(R.string.no_characters_desc), Modifier.padding(padding))
        } else {
            // 长按 grip 手柄拖动排序（与 API 配置页同款）；draggingName 标记正在拖动的卡
            val listState = rememberLazyListState()
            var draggingName by remember { mutableStateOf<String?>(null) }
            var dragOffset by remember { mutableFloatStateOf(0f) }
            var pointerY by remember { mutableFloatStateOf(0f) }

            fun listIndexOf(n: String) = names.indexOf(n).let { if (it < 0) -1 else it + 1 }

            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(Space12)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                itemsIndexed(names, key = { _, n -> n }) { idx, name ->
                    val c = chars[name] ?: return@itemsIndexed
                    val dragging = draggingName == name
                    Box(
                        Modifier
                            .then(if (dragging) Modifier else Modifier.animateItem())
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (dragging) dragOffset else 0f
                                if (dragging) { scaleX = 0.95f; scaleY = 0.95f }
                            }
                    ) {
                        CharCard(
                            c = c,
                            imageKey = imageVersion,
                            imageFile = CharacterRepository.imageFile(context, name),
                            onClick = { selectedChar = c; editingFileName = name },
                            onLongPress = { longPressName = name },
                            onDragStart = {
                                val li = idx + 1
                                val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == li }
                                pointerY = if (item != null) item.offset + item.size / 2f else 0f
                                draggingName = name
                                dragOffset = 0f
                            },
                            onDrag = { dy ->
                                val id = draggingName ?: return@CharCard
                                pointerY += dy
                                dragOffset += dy
                                val li = listIndexOf(id)
                                val info = listState.layoutInfo.visibleItemsInfo
                                val cur = info.firstOrNull { it.index == li }
                                val hovered = info.firstOrNull {
                                    it.index != li && it.index in 1..names.size &&
                                        pointerY >= it.offset && pointerY <= it.offset + it.size
                                }
                                if (cur != null && hovered != null) {
                                    val from = li - 1
                                    val to = hovered.index - 1
                                    names = names.toMutableList().also { it.add(to, it.removeAt(from)) }
                                    dragOffset -= (hovered.offset - cur.offset)
                                }
                            },
                            onDragEnd = {
                                if (draggingName != null) {
                                    scope.launch { CharacterRepository.saveOrder(context, names) }
                                }
                                draggingName = null; dragOffset = 0f
                            },
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
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun CharCard(
    c: CharacterCard,
    imageKey: Int,
    imageFile: java.io.File,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    // imageKey 变化即重新解码（编辑器换图后刷新）；文件不存在则为 null，回退到笑脸占位
    val bitmap = remember(imageFile.path, imageKey) {
        if (imageFile.exists()) {
            try { BitmapFactory.decodeFile(imageFile.path) } catch (_: Exception) { null }
        } else null
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(end = Space16),
        horizontalArrangement = Arrangement.spacedBy(Space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 点按/长按只在内容区生效：长按 grip 只触发拖拽，不会同时弹出导出/删除菜单
        Row(
            Modifier.weight(1f)
                .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress() }) }
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
            Modifier.size(24.dp).pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDrag = { change, amount -> change.consume(); onDrag(amount.y) },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
