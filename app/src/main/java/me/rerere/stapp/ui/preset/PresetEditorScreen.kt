package me.rerere.stapp.ui.preset

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.CircleChevronLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import me.rerere.stapp.R
import me.rerere.stapp.data.preset.PromptItem
import me.rerere.stapp.data.preset.StPreset

private val Space4 = 4.dp
private val Space8 = 8.dp
private val Space12 = 12.dp
private val Space16 = 16.dp

val SOURCES = listOf("openai", "claude", "makersuite", "custom", "openrouter", "google")
val ROLES = listOf("system", "user", "assistant")
val POSITIONS = listOf("Before Chat" to 0, "After Chat" to 1)

@Composable
fun PresetEditorScreen(
    preset: StPreset,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var editable by remember { mutableStateOf(preset) }
    var editingPrompt by remember { mutableStateOf<PromptItem?>(null) }

    // 系统返回键：先关闭弹窗，再执行返回导航
    BackHandler(enabled = editingPrompt == null, onBack = onBack)
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf(stringResource(R.string.basic_params), stringResource(R.string.prompts), stringResource(R.string.regex_tab))

    var deleteConfirmIdx by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Lucide.CircleChevronLeft, stringResource(R.string.back), Modifier.size(24.dp).clickable { onBack() },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        preset.name, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(24.dp))
                }
                PrimaryScrollableTabRow(
                    selectedTabIndex = tab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    edgePadding = 16.dp,
                ) {
                    tabs.forEachIndexed { i, title ->
                        val count = when (i) {
                            1 -> editable.prompts.size
                            2 -> 0
                            else -> null
                        }
                        Tab(i == tab, { tab = i }) {
                            Text(
                                if (count != null) "$title $count" else title,
                                Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> BasicParamsTab(editable) { editable = it }
                1 -> PromptsTab(
                    prompts = editable.prompts,
                    onEdit = { editingPrompt = it },
                    onToggle = { idx ->
                        editable = editable.copy(prompts = editable.prompts.toMutableList().also {
                            it[idx] = it[idx].copy(enabled = !it[idx].enabled)
                        })
                    },
                    onDeleteRequest = { idx -> deleteConfirmIdx = idx },
                    onAdd = {
                        // 只打开编辑框，保存时才真正加入列表
                        editingPrompt = PromptItem(
                            identifier = java.util.UUID.randomUUID().toString(),
                            name = context.getString(R.string.new_prompt),
                        )
                    }
                )
                2 -> RegexTab()
            }
        }
    }

    deleteConfirmIdx?.let { idx ->
        val name = editable.prompts.getOrNull(idx)?.name?.ifBlank { stringResource(R.string.unnamed_prompt) } ?: stringResource(R.string.unnamed_prompt)
        AlertDialog(
            onDismissRequest = { deleteConfirmIdx = null },
            title = { Text(stringResource(R.string.delete_prompt_title)) },
            text = { Text(stringResource(R.string.delete_prompt_msg_fmt, name)) },
            confirmButton = {
                TextButton(onClick = {
                    editable = editable.copy(
                        prompts = editable.prompts.toMutableList().also { it.removeAt(idx) }
                    )
                    deleteConfirmIdx = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmIdx = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    editingPrompt?.let { item ->
        PromptEditDialog(
            item = item,
            onDismiss = { editingPrompt = null },
            onSave = { updated ->
                editable = editable.copy(prompts = editable.prompts.toMutableList().also { l ->
                    val idx = l.indexOfFirst { it.identifier == item.identifier }
                    if (idx >= 0) l[idx] = updated else l.add(updated)
                })
                editingPrompt = null
            }
        )
    }
}

@Composable
private fun BasicParamsTab(p: StPreset, onUpdate: (StPreset) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space16),
        verticalArrangement = Arrangement.spacedBy(Space16)
    ) {
        SectionHeader(stringResource(R.string.sampling_params))
        SliderField("Temperature", p.temperature, 0f, 2f,
            stringResource(R.string.help_temperature)) { onUpdate(p.copy(temperature = it)) }
        SliderField("Top P", p.topP, 0f, 1f,
            stringResource(R.string.help_top_p)) { onUpdate(p.copy(topP = it)) }
        SliderField("Top K", p.topK.toFloat(), 0f, 200f,
            stringResource(R.string.help_top_k)) { onUpdate(p.copy(topK = it.toInt())) }
        NumberField("Frequency Penalty", p.frequencyPenalty,
            stringResource(R.string.help_frequency_penalty)) { onUpdate(p.copy(frequencyPenalty = it)) }
        NumberField("Presence Penalty", p.presencePenalty,
            stringResource(R.string.help_presence_penalty)) { onUpdate(p.copy(presencePenalty = it)) }
        NumberField("Repetition Penalty", p.repetitionPenalty,
            stringResource(R.string.help_repetition_penalty)) { onUpdate(p.copy(repetitionPenalty = it)) }

        SectionHeader(stringResource(R.string.context_params))
        NumberField("Max Context", p.maxContext.toFloat(),
            stringResource(R.string.help_max_context)) { onUpdate(p.copy(maxContext = it.toInt())) }
        NumberField("Max Tokens", p.maxTokens.toFloat(),
            stringResource(R.string.help_max_tokens)) { onUpdate(p.copy(maxTokens = it.toInt())) }
    }
}

@Composable
private fun PromptsTab(
    prompts: List<PromptItem>,
    onEdit: (PromptItem) -> Unit,
    onToggle: (Int) -> Unit,
    onDeleteRequest: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = Space16),
        verticalArrangement = Arrangement.spacedBy(Space8),
    ) {
        item { Spacer(Modifier.height(Space4)) }
        itemsIndexed(prompts, key = { _, p -> p.identifier }) { idx, item ->
            PromptRow(
                item = item,
                onEdit = { onEdit(item) },
                onToggle = { onToggle(idx) },
                onDelete = { onDeleteRequest(idx) },
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().clickable { onAdd() }.padding(Space16),
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Lucide.Plus, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Space8))
                Text(stringResource(R.string.add_prompt), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { Spacer(Modifier.height(Space8)) }
    }
}

@Composable
private fun PromptRow(
    item: PromptItem,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onEdit() }
            .padding(horizontal = Space12, vertical = Space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space12),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
            Text(item.name.ifBlank { stringResource(R.string.unnamed_prompt) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.content.isNotBlank()) {
                Text(item.content.take(60).replace("\n", " "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Switch(checked = item.enabled, onCheckedChange = { onToggle() })
        Icon(Lucide.Trash2, stringResource(R.string.delete), Modifier.size(18.dp).clickable { onDelete() },
            tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun RegexTab() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space8)) {
            Text(stringResource(R.string.regex_tab), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.regex_coming_soon), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PromptEditDialog(
    item: PromptItem,
    onDismiss: () -> Unit,
    onSave: (PromptItem) -> Unit,
) {
    var name by remember { mutableStateOf(item.name) }
    var role by remember { mutableStateOf(item.role) }
    var content by remember { mutableStateOf(item.content) }
    var injectionPos by remember { mutableIntStateOf(item.injectionPosition) }
    var injectionDepth by remember { mutableStateOf(item.injectionDepth.toString()) }
    var forbidOverrides by remember { mutableStateOf(item.forbidOverrides) }
    var systemPrompt by remember { mutableStateOf(item.systemPrompt) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                        Text(
                            stringResource(R.string.edit_prompt), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                        )
                        Button(onClick = {
                            onSave(item.copy(
                                name = name, role = role, content = content,
                                injectionPosition = injectionPos,
                                injectionDepth = injectionDepth.toIntOrNull() ?: item.injectionDepth,
                                forbidOverrides = forbidOverrides, systemPrompt = systemPrompt,
                            ))
                        }) { Text(stringResource(R.string.save)) }
                    }
                }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(Space16),
                    verticalArrangement = Arrangement.spacedBy(Space16),
                ) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text(stringResource(R.string.prompt_name)) }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    DropdownField(stringResource(R.string.prompt_role), role, ROLES) { role = it }

                    DropdownField(
                        stringResource(R.string.injection_position),
                        POSITIONS.first { it.second == injectionPos }.first,
                        POSITIONS.map { it.first }
                    ) { label ->
                        injectionPos = POSITIONS.first { it.first == label }.second
                    }

                    OutlinedTextField(
                        value = injectionDepth, onValueChange = { injectionDepth = it },
                        label = { Text(stringResource(R.string.injection_depth)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )

                    SwitchField(stringResource(R.string.system_prompt_label), systemPrompt) { systemPrompt = it }
                    SwitchField(stringResource(R.string.forbid_overrides_label), forbidOverrides) { forbidOverrides = it }

                    Column(verticalArrangement = Arrangement.spacedBy(Space8)) {
                        Text(stringResource(R.string.prompt_content), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)

                        // 正文输入框占据剩余空间，可独立滚动
                        OutlinedTextField(
                            value = content, onValueChange = { content = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SliderField(
    label: String, value: Float, min: Float, max: Float,
    help: String = "",
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%.2f".format(value), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
        if (help.isNotBlank()) {
            Text(help, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun NumberField(
    label: String, value: Float, help: String = "", onChange: (Float) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toLong().toString()) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = text, onValueChange = { v ->
                    text = v; v.toFloatOrNull()?.let { onChange(it) }
                },
                singleLine = true, modifier = Modifier.width(120.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        if (help.isNotBlank()) {
            Text(help, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun DropdownField(
    label: String, current: String, options: List<String>,
    help: String = "", onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { expanded = true }
                        .padding(horizontal = Space12, vertical = Space8),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space4)
                ) {
                    Text(current, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Icon(Lucide.ChevronDown, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { opt ->
                        DropdownMenuItem(
                            text = { Text(opt) },
                            onClick = { onChange(opt); expanded = false },
                        )
                    }
                }
            }
        }
        if (help.isNotBlank()) {
            Text(help, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun SwitchField(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
