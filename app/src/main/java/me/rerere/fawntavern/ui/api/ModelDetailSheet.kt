package me.rerere.fawntavern.ui.api

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.BuiltInTool
import me.rerere.fawntavern.data.api.KeyValue
import me.rerere.fawntavern.data.api.Modality
import me.rerere.fawntavern.data.api.ModelAbility
import me.rerere.fawntavern.data.api.ModelInfo
import me.rerere.fawntavern.data.api.ModelRegistry
import me.rerere.fawntavern.data.api.supportedBy
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16

/**
 * 模型详情底部面板：基本设置（ID / 名称 / 模态 / 能力）、高级设置（自定义请求头与请求体）、
 * 内置工具三个 Tab。新增与编辑共用，[isNew] 决定标题、确认按钮文案，以及模型 ID 是否可改
 * （已添加的模型 ID 同时是 ThinkingStore 与 ApiConfig.currentModel 的键，改了会丢档位/选中状态）。
 *
 * 改动只落在本地草稿上，点确认才回传给调用方落盘。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelDetailSheet(
    model: ModelInfo,
    provider: ApiProvider,
    isNew: Boolean,
    onConfirm: (ModelInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(model) }
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.model_basic_tab),
        stringResource(R.string.model_advanced_tab),
        stringResource(R.string.model_builtin_tools_tab),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding()) {
            Text(
                stringResource(if (isNew) R.string.add_model else R.string.edit_model),
                Modifier.fillMaxWidth().padding(horizontal = Space16, vertical = Space4),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            PrimaryTabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                tabs.forEachIndexed { i, title ->
                    Tab(i == tab, { tab = i }) {
                        Text(title, Modifier.padding(vertical = Space12),
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(Space16),
                verticalArrangement = Arrangement.spacedBy(Space16),
            ) {
                when (tab) {
                    0 -> BasicTab(draft, isNew) { draft = it }
                    1 -> AdvancedTab(draft) { draft = it }
                    else -> BuiltInToolsTab(draft, provider) { draft = it }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space16, vertical = Space8),
                horizontalArrangement = Arrangement.spacedBy(Space8, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = { onConfirm(draft.copy(id = draft.id.trim())) },
                    enabled = draft.id.isNotBlank(),
                ) {
                    Text(stringResource(if (isNew) R.string.add_model else R.string.confirm))
                }
            }
        }
    }
}

@Composable
private fun BasicTab(model: ModelInfo, isNew: Boolean, update: (ModelInfo) -> Unit) {
    OutlinedTextField(
        value = model.id,
        onValueChange = { v ->
            // 新建时随 ID 重新猜模态与能力；高级设置/内置工具的改动保留
            val id = v.trim()
            val caps = ModelRegistry.infer(id)
            update(model.copy(
                id = id, displayName = id,
                inputModalities = caps.input, outputModalities = caps.output,
                abilities = caps.abilities,
            ))
        },
        label = { Text(stringResource(R.string.model_id_label)) },
        placeholder = { Text("gpt-4o") },
        singleLine = true,
        enabled = isNew,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = model.displayName,
        onValueChange = { update(model.copy(displayName = it)) },
        label = { Text(stringResource(R.string.model_name_label)) },
        placeholder = { Text(model.id) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Field(stringResource(R.string.model_input_modality)) {
        ModalityRow(model.inputModalities) { update(model.copy(inputModalities = it)) }
    }
    Field(stringResource(R.string.model_output_modality)) {
        ModalityRow(model.outputModalities) { update(model.copy(outputModalities = it)) }
    }
    Field(stringResource(R.string.model_abilities)) {
        MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ModelAbility.entries.forEachIndexed { i, ability ->
                SegmentedButton(
                    checked = ability in model.abilities,
                    onCheckedChange = { checked ->
                        val next = if (checked) model.abilities + ability else model.abilities - ability
                        update(model.copy(abilities = next.sortedBy { it.ordinal }))
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, ModelAbility.entries.size),
                    label = { Text(ability.label()) },
                )
            }
        }
    }
    Text(stringResource(R.string.model_abilities_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AdvancedTab(model: ModelInfo, update: (ModelInfo) -> Unit) {
    Text(stringResource(R.string.custom_request_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    KeyValueSection(
        title = stringResource(R.string.custom_headers),
        addLabel = stringResource(R.string.add_header),
        items = model.headers,
        valuePlaceholder = null,
        onChange = { update(model.copy(headers = it)) },
    )
    KeyValueSection(
        title = stringResource(R.string.custom_bodies),
        addLabel = stringResource(R.string.add_body_field),
        items = model.bodies,
        valuePlaceholder = stringResource(R.string.custom_body_value_hint),
        onChange = { update(model.copy(bodies = it)) },
    )
}

@Composable
private fun BuiltInToolsTab(model: ModelInfo, provider: ApiProvider, update: (ModelInfo) -> Unit) {
    Text(stringResource(R.string.builtin_tools_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    BuiltInTool.entries.forEach { tool ->
        // 能不能开取决于协议/端点而非模型：同一个模型换个网关就没有对应字段了
        val supported = tool.supportedBy(provider)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = Space16, vertical = Space12),
                horizontalArrangement = Arrangement.spacedBy(Space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                    Text(tool.label(), style = MaterialTheme.typography.titleSmall,
                        color = if (supported) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (supported) tool.description()
                        else stringResource(R.string.builtin_tool_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = supported && tool in model.tools,
                    enabled = supported,
                    onCheckedChange = { on ->
                        update(model.copy(tools = if (on) model.tools + tool else model.tools - tool))
                    },
                )
            }
        }
    }
}

/** 标题 + 控件（外层 Column 的 16dp 间距对标题与控件之间太宽，这里收成 8dp） */
@Composable
private fun Field(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space8)) {
        Text(label, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun ModalityRow(selected: List<Modality>, onChange: (List<Modality>) -> Unit) {
    MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Modality.entries.forEachIndexed { i, modality ->
            SegmentedButton(
                checked = modality in selected,
                onCheckedChange = { checked ->
                    val next = if (checked) selected + modality else selected - modality
                    // 一种模态都不选的模型没有意义，全关掉时退回纯文本
                    onChange(next.ifEmpty { listOf(Modality.TEXT) }.sortedBy { it.ordinal })
                },
                shape = SegmentedButtonDefaults.itemShape(i, Modality.entries.size),
                label = { Text(modality.label()) },
            )
        }
    }
}

/** 自定义请求头/请求体的条目编辑：键一行（带删除），值一行 */
@Composable
private fun KeyValueSection(
    title: String,
    addLabel: String,
    items: List<KeyValue>,
    valuePlaceholder: String?,
    onChange: (List<KeyValue>) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space8)) {
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEachIndexed { idx, kv ->
            Column(verticalArrangement = Arrangement.spacedBy(Space4)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = kv.key,
                        onValueChange = { v ->
                            onChange(items.toMutableList().also { it[idx] = kv.copy(key = v) })
                        },
                        label = { Text(stringResource(R.string.kv_key)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    AppIconButton(
                        icon = Lucide.Trash2,
                        contentDescription = stringResource(R.string.delete),
                        onClick = { onChange(items.toMutableList().also { it.removeAt(idx) }) },
                        iconSize = 20.dp,
                    )
                }
                OutlinedTextField(
                    value = kv.value,
                    onValueChange = { v ->
                        onChange(items.toMutableList().also { it[idx] = kv.copy(value = v) })
                    },
                    label = { Text(stringResource(R.string.kv_value)) },
                    placeholder = valuePlaceholder?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        OutlinedButton(
            onClick = { onChange(items + KeyValue()) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Icon(Lucide.Plus, null, Modifier.size(18.dp))
            Spacer(Modifier.width(Space8))
            Text(addLabel)
        }
    }
}

@Composable
private fun BuiltInTool.description(): String = stringResource(
    when (this) {
        BuiltInTool.SEARCH -> R.string.builtin_tool_search_desc
        BuiltInTool.URL_CONTEXT -> R.string.builtin_tool_url_context_desc
    }
)
