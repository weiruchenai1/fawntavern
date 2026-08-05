package me.rerere.fawntavern.ui.character

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ImagePlus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquareText
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiConfigStore
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.settings.CharacterModelStore
import me.rerere.fawntavern.data.worldbook.WorldBookRepository
import me.rerere.fawntavern.ui.chat.ModelPickerSheet
import me.rerere.fawntavern.ui.components.AppTopBar
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.ConfirmDeleteDialog
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.settings.ModelCard
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CharacterEditorScreen(card: CharacterCard, onBack: () -> Unit, cardFileName: String = card.name) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(card.name) }
    var description by remember { mutableStateOf(card.description) }
    var personality by remember { mutableStateOf(card.personality) }
    var scenario by remember { mutableStateOf(card.scenario) }
    var systemPrompt by remember { mutableStateOf(card.systemPrompt) }
    var postHistory by remember { mutableStateOf(card.postHistoryInstructions) }
    var mesExample by remember { mutableStateOf(card.mesExample) }
    var creatorNotes by remember { mutableStateOf(card.creatorNotes) }
    var depthPromptText by remember { mutableStateOf(card.depthPrompt?.prompt ?: "") }
    var depthPromptDepth by remember { mutableStateOf((card.depthPrompt?.depth ?: 4).toString()) }
    var depthPromptRole by remember { mutableStateOf(card.depthPrompt?.role ?: "system") }
    var tags by remember { mutableStateOf(card.tags) }
    var enabledWb by remember { mutableStateOf(card.enabledWorldBooks) }
    var linkedPreset by remember { mutableStateOf(card.linkedPreset) }
    var streaming by remember { mutableStateOf(card.streaming) }
    var greetings by remember {
        mutableStateOf(
            buildList {
                if (card.firstMes.isNotBlank()) add(card.firstMes)
                addAll(card.alternateGreetings.filter { it.isNotBlank() })
            }
        )
    }

    var showAddTagDialog by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    val charModelStore = remember { CharacterModelStore }
    val charModelKey = card.name.ifBlank { cardFileName }
    var charModel by remember { mutableStateOf(charModelStore.get(context, charModelKey)) }
    var showGreetingDialog by remember { mutableStateOf(false) }
    var editingGreetingIdx by remember { mutableStateOf<Int?>(null) }
    var deletingGreetingIdx by remember { mutableStateOf<Int?>(null) }
    var advancedExpanded by remember { mutableStateOf(false) }

    // 角色卡图片：导入 PNG 时保留、可在弹窗里更换/移除；更换后主界面/抽屉头像随之更新
    val imageFile = remember(cardFileName) { CharacterRepository.imageFile(context, cardFileName) }
    var imageVersion by remember { mutableStateOf(0) }
    val imageBitmap = remember(imageFile.path, imageVersion) {
        if (imageFile.exists()) {
            try { BitmapFactory.decodeFile(imageFile.path) } catch (_: Exception) { null }
        } else null
    }
    var showImageDialog by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            if (CharacterRepository.saveImageFromUri(context, cardFileName, uri)) imageVersion++
        }
    }

    // 图片编辑弹窗
    if (showImageDialog) {
        AlertDialog(
            onDismissRequest = { showImageDialog = false },
            title = { Text(stringResource(R.string.character_avatar)) },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space12)) {
                    // 当前图片预览
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space12)) {
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            val bmp = imageBitmap
                            if (bmp != null) {
                                Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop)
                            } else {
                                Icon(Lucide.ImagePlus, null, Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(name.ifBlank { cardFileName },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }

                    OutlinedButton(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Lucide.ImagePlus, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Space8))
                        Text(stringResource(R.string.pick_from_gallery))
                    }

                    if (imageBitmap != null) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    CharacterRepository.deleteImage(context, cardFileName)
                                    imageVersion++
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Lucide.Trash2, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(Space8))
                            Text(stringResource(R.string.remove_image),
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImageDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    // 就地修改角色卡 JSON 文件的 data 节点
    suspend fun patchCard(block: (JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        try {
            val f = File(CharacterRepository.charsDir(context), "$cardFileName.json")
            if (f.exists()) {
                val json = JSONObject(f.readText())
                val d = json.optJSONObject("data") ?: json
                block(d)
                f.writeText(json.toString(2))
            }
        } catch (_: Exception) {}
    }

    // 返回时保存全部可编辑字段
    fun saveAndBack() {
        scope.launch {
            patchCard { d ->
                d.put("name", name.trim())
                d.put("description", description)
                d.put("personality", personality)
                d.put("scenario", scenario)
                d.put("system_prompt", systemPrompt)
                d.put("post_history_instructions", postHistory)
                d.put("mes_example", mesExample)
                d.put("creator_notes", creatorNotes)
                d.put("tags", JSONArray(tags))
                d.put("first_mes", greetings.firstOrNull() ?: "")
                d.put("alternate_greetings", JSONArray(greetings.drop(1)))
                d.put("enabled_world_books", JSONArray(enabledWb))
                d.put("linked_preset", linkedPreset)
                d.put("streaming", streaming)
                // 角色注入提示写回 extensions.depth_prompt（空则移除）
                val ext = d.optJSONObject("extensions") ?: JSONObject().also { d.put("extensions", it) }
                if (depthPromptText.isBlank()) {
                    ext.remove("depth_prompt")
                } else {
                    ext.put("depth_prompt", JSONObject()
                        .put("prompt", depthPromptText)
                        .put("depth", depthPromptDepth.toIntOrNull() ?: 4)
                        .put("role", depthPromptRole))
                }
            }
            onBack()
        }
    }

    // API 配置：角色卡模型选择器和模型选择面板都要用
    val apiConfig = remember { ApiConfigStore.loadConfig(context) }

    BackHandler { saveAndBack() }

    if (showAddTagDialog) {
        var newTag by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTagDialog = false },
            title = { Text(stringResource(R.string.add_tag)) },
            text = {
                OutlinedTextField(value = newTag, onValueChange = { newTag = it },
                    label = { Text(stringResource(R.string.tag_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newTag.isNotBlank()) tags = tags + newTag.trim()
                    showAddTagDialog = false
                }) { Text(stringResource(R.string.add_button)) }
            },
            dismissButton = { TextButton(onClick = { showAddTagDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showGreetingDialog || editingGreetingIdx != null) {
        val idx = editingGreetingIdx
        var greeting by remember(idx) {
            mutableStateOf(if (idx != null) greetings.getOrElse(idx) { "" } else "")
        }
        AlertDialog(
            onDismissRequest = { showGreetingDialog = false; editingGreetingIdx = null },
            title = { Text(if (idx != null) stringResource(R.string.edit_greeting) else stringResource(R.string.add_greeting)) },
            text = {
                OutlinedTextField(
                    value = greeting, onValueChange = { greeting = it },
                    label = { Text(stringResource(R.string.greeting_content)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = greeting.trim()
                    if (trimmed.isNotBlank()) {
                        greetings = if (idx != null) {
                            greetings.toMutableList().also { it[idx] = trimmed }
                        } else {
                            greetings + trimmed
                        }
                    }
                    showGreetingDialog = false; editingGreetingIdx = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showGreetingDialog = false; editingGreetingIdx = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    deletingGreetingIdx?.let { idx ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.delete_greeting),
            text = stringResource(R.string.delete_greeting_msg),
            onConfirm = {
                if (idx < greetings.size) greetings = greetings.toMutableList().also { it.removeAt(idx) }
                deletingGreetingIdx = null
            },
            onDismiss = { deletingGreetingIdx = null },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                if (card.name.isBlank()) stringResource(R.string.new_character) else stringResource(R.string.edit_character),
                onBack = { saveAndBack() },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(Space16),
            verticalArrangement = Arrangement.spacedBy(Space16),
        ) {
            // 角色卡图片：点击弹出编辑弹窗；无图时显示占位
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(120.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { showImageDialog = true },
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = imageBitmap
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), stringResource(R.string.character_avatar),
                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Space4)) {
                            Icon(Lucide.ImagePlus, null, Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.change_image),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.char_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(Space8)) {
                Text(stringResource(R.string.tag_label), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space4),
                    verticalArrangement = Arrangement.spacedBy(Space4)) {
                    tags.forEach { tag ->
                        InputChip(
                            selected = false, onClick = {},
                            label = { Text(tag) },
                            trailingIcon = {
                                Icon(Lucide.X, stringResource(R.string.delete), Modifier.size(16.dp).clip(CircleShape).clickable {
                                    tags = tags - tag
                                })
                            },
                        )
                    }
                    InputChip(selected = false, onClick = { showAddTagDialog = true },
                        label = { Text(stringResource(R.string.add_button)) },
                        leadingIcon = { Icon(Lucide.Plus, null, Modifier.size(16.dp)) },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space8)) {
                Text(stringResource(R.string.greetings_label), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                greetings.forEachIndexed { idx, g ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { editingGreetingIdx = idx }
                            .padding(Space12),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(g.take(50).replace("\n", " "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        AppIconButton(
                            icon = Lucide.Trash2,
                            contentDescription = stringResource(R.string.delete),
                            onClick = { deletingGreetingIdx = idx },
                            size = 32.dp,
                            iconSize = 16.dp,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { showGreetingDialog = true }.padding(Space8),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Plus, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(Space4))
                    Text(stringResource(R.string.add_greeting), color = MaterialTheme.colorScheme.primary)
                }
            }

            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text(stringResource(R.string.char_definition_label)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 600.dp),
            )

            // 高级定义（Advanced Definitions）：默认折叠，避免冲淡基础编辑
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { advancedExpanded = !advancedExpanded }
                    .padding(horizontal = Space12, vertical = Space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                    Text(stringResource(R.string.advanced_definitions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.advanced_definitions_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (advancedExpanded) Lucide.ChevronDown else Lucide.ChevronRight, null,
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (advancedExpanded) {
                OutlinedTextField(
                    value = personality, onValueChange = { personality = it },
                    label = { Text(stringResource(R.string.char_personality_label)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 300.dp),
                )
                OutlinedTextField(
                    value = scenario, onValueChange = { scenario = it },
                    label = { Text(stringResource(R.string.char_scenario_label)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 300.dp),
                )
                OutlinedTextField(
                    value = systemPrompt, onValueChange = { systemPrompt = it },
                    label = { Text(stringResource(R.string.char_system_prompt_label)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 300.dp),
                )
                OutlinedTextField(
                    value = postHistory, onValueChange = { postHistory = it },
                    label = { Text(stringResource(R.string.char_post_history_label)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 300.dp),
                )
                OutlinedTextField(
                    value = mesExample, onValueChange = { mesExample = it },
                    label = { Text(stringResource(R.string.char_mes_example_label)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 300.dp),
                )
                OutlinedTextField(
                    value = creatorNotes, onValueChange = { creatorNotes = it },
                    label = { Text(stringResource(R.string.char_creator_notes_label)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                )
                // 角色注入提示（Character's Note）：正文 + 注入深度 + 角色（始终显示）
                OutlinedTextField(
                    value = depthPromptText, onValueChange = { depthPromptText = it },
                    label = { Text(stringResource(R.string.char_depth_prompt_label)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space12),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = depthPromptDepth, onValueChange = { depthPromptDepth = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.char_depth_prompt_depth)) },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    RoleDropdown(
                        label = stringResource(R.string.char_depth_prompt_role),
                        current = depthPromptRole,
                        modifier = Modifier.weight(1f),
                    ) { depthPromptRole = it }
                }
            }

            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable {
                        streaming = !streaming
                        scope.launch { patchCard { it.put("streaming", streaming) } }
                    }
                    .padding(horizontal = Space12, vertical = Space8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space12),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                    Text(stringResource(R.string.stream_output_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.stream_output_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = streaming, onCheckedChange = {
                    streaming = it
                    scope.launch { patchCard { d -> d.put("streaming", it) } }
                })
            }

            // 角色默认聊天模型（未设置时使用全局默认）
            ModelCard(
                icon = Lucide.MessageSquareText,
                title = stringResource(R.string.char_default_model),
                subtitle = stringResource(R.string.char_default_model_desc),
                iconKey = charModel.substringAfter("::", "").takeIf { charModel.isNotBlank() } ?: "",
                displayName = if (charModel.isNotBlank()) {
                    val p = apiConfig.providers.find { it.id == charModel.substringBefore("::") && it.enabled }
                    val mid = charModel.substringAfter("::", "")
                    p?.models?.find { it.id == mid }?.name ?: mid.ifBlank { charModel }
                } else stringResource(R.string.default_model_use_global),
                showReset = charModel.isNotBlank(),
                showBolt = false,
                onPick = { showModelPicker = true },
                onReset = { charModelStore.set(context, charModelKey, ""); charModel = "" },
            )

            WorldBookSelector(
                enabledNames = enabledWb,
                onToggle = { wbName ->
                    val updated = if (wbName in enabledWb) enabledWb - wbName else enabledWb + wbName
                    enabledWb = updated
                    scope.launch { patchCard { it.put("enabled_world_books", JSONArray(updated)) } }
                }
            )

            PresetSelector(
                selected = linkedPreset,
                onSelect = { sel ->
                    linkedPreset = sel
                    scope.launch { patchCard { it.put("linked_preset", sel) } }
                }
            )
        }
    }

    // 角色默认模型选择面板
    if (showModelPicker) {
        ModelPickerSheet(
            providers = apiConfig.providers,
            currentModel = charModel,
            onSelect = { providerId, modelId ->
                val spec = "$providerId::$modelId"
                charModelStore.set(context, charModelKey, spec)
                charModel = spec
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
}

@Composable
private fun PresetSelector(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    var presetNames by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        presetNames = PresetRepository.listNames(context)
    }

    if (presetNames.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(Space8)) {
        Text(stringResource(R.string.assoc_preset), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = Space4))
        presetNames.forEach { name ->
            val checked = name == selected
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onSelect(if (checked) "" else name) }
                    .padding(horizontal = Space8, vertical = Space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = checked, onClick = { onSelect(if (checked) "" else name) })
                Spacer(Modifier.width(Space4))
                Icon(Lucide.SlidersHorizontal, null, Modifier.size(16.dp),
                    tint = if (checked) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(Space8))
                Text(name, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun WorldBookSelector(
    enabledNames: List<String>,
    onToggle: (String) -> Unit,
) {
    val context = LocalContext.current
    var bookNames by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        bookNames = withContext(Dispatchers.IO) { WorldBookRepository.listNames(context) }
    }

    if (bookNames.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(Space8)) {
        Row(Modifier.fillMaxWidth().padding(vertical = Space4), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.assoc_worldbooks), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
        }
        bookNames.forEach { name ->
            val checked = name in enabledNames
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onToggle(name) }
                    .padding(horizontal = Space8, vertical = Space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { onToggle(name) })
                Spacer(Modifier.width(Space4))
                Icon(Lucide.BookOpen, null, Modifier.size(16.dp),
                    tint = if (checked) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(Space8))
                Text(name, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val ROLE_OPTIONS = listOf("system", "user", "assistant")

@Composable
private fun roleLabel(role: String): String = when (role) {
    "user" -> stringResource(R.string.role_user)
    "assistant" -> stringResource(R.string.role_assistant)
    else -> stringResource(R.string.role_system)
}

/** 注入角色下拉（system/user/assistant）：readOnly 输入框 + 透明覆盖层接管点击 */
@Composable
private fun RoleDropdown(
    label: String, current: String, modifier: Modifier = Modifier, onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = roleLabel(current), onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(label) },
            trailingIcon = { Icon(Lucide.ChevronDown, null, Modifier.size(20.dp)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ROLE_OPTIONS.forEach { r ->
                DropdownMenuItem(text = { Text(roleLabel(r)) }, onClick = { onChange(r); expanded = false })
            }
        }
    }
}
