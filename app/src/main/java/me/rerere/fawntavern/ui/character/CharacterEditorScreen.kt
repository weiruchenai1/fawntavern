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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.ImagePlus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquareText
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import kotlinx.coroutines.launch
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.ui.components.ModelSelectorSheet
import me.rerere.fawntavern.ui.components.rememberModelSelectorState
import me.rerere.fawntavern.ui.components.AppTopBar
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.AppTextArea
import me.rerere.fawntavern.ui.components.ConfirmDeleteDialog
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.settings.ModelCard
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditorScreen(card: CharacterCard, onBack: () -> Unit, cardFileName: String = card.name) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val controller = remember(context) {
        CharacterEditorController(AndroidCharacterEditorDataSource(context))
    }
    var name by remember { mutableStateOf(card.name) }
    // 多行字段用 TextFieldState（BTF2）：限高交给 AppTextArea 的 lineLimits，滚动在测量期完成
    val description = rememberTextFieldState(card.description)
    val personality = rememberTextFieldState(card.personality)
    val scenario = rememberTextFieldState(card.scenario)
    val systemPrompt = rememberTextFieldState(card.systemPrompt)
    val postHistory = rememberTextFieldState(card.postHistoryInstructions)
    val mesExample = rememberTextFieldState(card.mesExample)
    val creatorNotes = rememberTextFieldState(card.creatorNotes)
    val depthPromptText = rememberTextFieldState(card.depthPrompt?.prompt ?: "")
    var depthPromptDepth by remember { mutableStateOf((card.depthPrompt?.depth ?: 4).toString()) }
    var depthPromptRole by remember { mutableStateOf(card.depthPrompt?.role ?: "system") }
    var tags by remember {
        mutableStateOf(
            card.tags
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase() },
        )
    }
    var enabledWb by remember { mutableStateOf(card.enabledWorldBooks) }
    var linkedPreset by remember { mutableStateOf(card.linkedPreset) }
    var linkedRegex by remember { mutableStateOf(card.linkedRegex) }
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
    val charModelKey = card.name.ifBlank { cardFileName }
    var charModel by remember { mutableStateOf(controller.model(charModelKey)) }
    var showGreetingDialog by remember { mutableStateOf(false) }
    var editingGreetingIdx by remember { mutableStateOf<Int?>(null) }
    var deletingGreetingIdx by remember { mutableStateOf<Int?>(null) }
    var advancedExpanded by remember { mutableStateOf(false) }

    // 角色卡图片：导入 PNG 时保留、可在弹窗里更换/移除；更换后主界面/抽屉头像随之更新
    val imageFile = remember(cardFileName) { controller.imageFile(cardFileName) }
    var imageVersion by remember { mutableIntStateOf(0) }
    val imageBitmap = remember(imageFile.path, imageVersion) {
        if (imageFile.exists()) {
            try { BitmapFactory.decodeFile(imageFile.path) } catch (_: Exception) { null }
        } else null
    }
    var showImageDialog by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            if (controller.saveImage(cardFileName, uri)) imageVersion++
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
                                    controller.deleteImage(cardFileName)
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

    suspend fun patchCard(block: (JSONObject) -> Unit): Boolean = try {
        controller.updateJson(cardFileName, block)
        true
    } catch (e: Exception) {
        Toast.makeText(
            context,
            resources.getString(R.string.char_save_failed_fmt, e.message.orEmpty()),
            Toast.LENGTH_SHORT,
        ).show()
        false
    }

    // 返回时保存全部可编辑字段
    fun saveAndBack() {
        scope.launch {
            val saved = patchCard { d ->
                d.put("name", name.trim())
                d.put("description", description.text.toString())
                d.put("personality", personality.text.toString())
                d.put("scenario", scenario.text.toString())
                d.put("system_prompt", systemPrompt.text.toString())
                d.put("post_history_instructions", postHistory.text.toString())
                d.put("mes_example", mesExample.text.toString())
                d.put("creator_notes", creatorNotes.text.toString())
                d.put("tags", JSONArray(tags))
                d.put("first_mes", greetings.firstOrNull() ?: "")
                d.put("alternate_greetings", JSONArray(greetings.drop(1)))
                d.put("enabled_world_books", JSONArray(enabledWb))
                d.put("linked_preset", linkedPreset)
                d.put("linked_regex", linkedRegex)
                d.put("streaming", streaming)
                // 角色注入提示写回 extensions.depth_prompt（空则移除）
                val ext = d.optJSONObject("extensions") ?: JSONObject().also { d.put("extensions", it) }
                if (depthPromptText.text.isBlank()) {
                    ext.remove("depth_prompt")
                } else {
                    ext.put("depth_prompt", JSONObject()
                        .put("prompt", depthPromptText.text.toString())
                        .put("depth", depthPromptDepth.toIntOrNull() ?: 4)
                        .put("role", depthPromptRole))
                }
            }
            if (saved) onBack()
        }
    }

    // API 配置：角色卡模型选择器和模型选择面板都要用
    val apiConfig = remember(controller) { controller.apiConfig() }

    // 角色默认模型选择面板
    val modelSelector = rememberModelSelectorState(charModel, apiConfig.providers)

    BackHandler { saveAndBack() }

    if (showAddTagDialog) {
        var newTag by remember { mutableStateOf("") }
        val normalizedTag = newTag.trim()
        val tagExists = tags.any { it.equals(normalizedTag, ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { showAddTagDialog = false },
            title = { Text(stringResource(R.string.add_tag)) },
            text = {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    label = { Text(stringResource(R.string.tag_label)) },
                    singleLine = true,
                    isError = tagExists,
                    supportingText = if (tagExists) {
                        { Text(stringResource(R.string.tag_already_exists)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (tags.none { it.equals(normalizedTag, ignoreCase = true) }) {
                            tags = tags + normalizedTag
                        }
                        showAddTagDialog = false
                    },
                    enabled = normalizedTag.isNotEmpty() && !tagExists,
                ) { Text(stringResource(R.string.add_button)) }
            },
            dismissButton = { TextButton(onClick = { showAddTagDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showGreetingDialog || editingGreetingIdx != null) {
        val idx = editingGreetingIdx
        val greeting = remember(idx) {
            TextFieldState(if (idx != null) greetings.getOrElse(idx) { "" } else "")
        }
        val greetingSheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(
            onDismissRequest = { showGreetingDialog = false; editingGreetingIdx = null },
            sheetState = greetingSheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.8f)
                    .padding(horizontal = 16.dp).imePadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (idx != null) stringResource(R.string.edit_greeting)
                        else stringResource(R.string.add_greeting),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        val trimmed = greeting.text.toString().trim()
                        if (trimmed.isNotBlank()) {
                            greetings = if (idx != null) {
                                greetings.toMutableList().also { it[idx] = trimmed }
                            } else {
                                greetings + trimmed
                            }
                        }
                        showGreetingDialog = false
                        editingGreetingIdx = null
                    }) {
                        Text(stringResource(R.string.confirm))
                    }
                }
                androidx.compose.foundation.text.BasicTextField(
                    state = greeting,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 24.dp),
                )
            }
        }
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
        modifier = Modifier.imePadding(),
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
                .verticalScroll(rememberScrollState())
                .padding(Space16),
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

            AppTextArea(
                state = description,
                label = stringResource(R.string.char_definition_label),
                minLines = 8, maxLines = 24,
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
                AppTextArea(
                    state = personality,
                    label = stringResource(R.string.char_personality_label),
                )
                AppTextArea(
                    state = scenario,
                    label = stringResource(R.string.char_scenario_label),
                )
                AppTextArea(
                    state = systemPrompt,
                    label = stringResource(R.string.char_system_prompt_label),
                )
                AppTextArea(
                    state = postHistory,
                    label = stringResource(R.string.char_post_history_label),
                )
                AppTextArea(
                    state = mesExample,
                    label = stringResource(R.string.char_mes_example_label),
                )
                AppTextArea(
                    state = creatorNotes,
                    label = stringResource(R.string.char_creator_notes_label),
                    minLines = 2,
                )
                // 角色注入提示（Character's Note）：正文 + 注入深度 + 角色（始终显示）
                AppTextArea(
                    state = depthPromptText,
                    label = stringResource(R.string.char_depth_prompt_label),
                    minLines = 2,
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
                onPick = { modelSelector.open() },
                onReset = { controller.saveModel(charModelKey, ""); charModel = "" },
            )

            WorldBookSelector(
                controller = controller,
                enabledNames = enabledWb,
                onToggle = { wbName ->
                    val updated = if (wbName in enabledWb) enabledWb - wbName else enabledWb + wbName
                    enabledWb = updated
                }
            )

            PresetSelector(
                controller = controller,
                selected = linkedPreset,
                onSelect = { sel ->
                    linkedPreset = sel
                }
            )

            RegexSelector(
                controller = controller,
                currentCardFile = cardFileName,
                selected = linkedRegex,
                onSelect = { linkedRegex = it },
            )
        }
    }

    // 角色默认模型选择面板
    ModelSelectorSheet(
        state = modelSelector,
        onSelect = { providerId, modelId ->
            val spec = "$providerId::$modelId"
            controller.saveModel(charModelKey, spec)
            charModel = spec
        },
    )
}

@Composable
private fun PresetSelector(
    controller: CharacterEditorController,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var presetNames by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        presetNames = controller.presetNames()
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
private fun RegexSelector(
    controller: CharacterEditorController,
    currentCardFile: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var options by remember { mutableStateOf<List<CharacterRegexOption>>(emptyList()) }

    LaunchedEffect(currentCardFile) {
        options = controller.regexOptions()
    }

    if (options.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(Space8)) {
        Text(
            stringResource(R.string.assoc_regex),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = Space4),
        )
        options.forEach { option ->
            val checked = option.id == selected
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onSelect(if (checked) "" else option.id) }
                    .padding(horizontal = Space8, vertical = Space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = checked,
                    onClick = { onSelect(if (checked) "" else option.id) },
                )
                Spacer(Modifier.width(Space4))
                Icon(
                    Lucide.FileJson,
                    null,
                    Modifier.size(16.dp),
                    tint = if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(Space8))
                Text(
                    option.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WorldBookSelector(
    controller: CharacterEditorController,
    enabledNames: List<String>,
    onToggle: (String) -> Unit,
) {
    var bookNames by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        bookNames = controller.worldBookNames()
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
