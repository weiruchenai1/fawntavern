package me.rerere.fawntavern.ui.api

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Package
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ModelInfo
import me.rerere.fawntavern.data.api.ModelType
import me.rerere.fawntavern.data.api.Modality
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderModelTab(
    prov: ApiProvider,
    update: (ApiProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    var adding by remember { mutableStateOf(false) }
    var editingIdx by remember { mutableStateOf<Int?>(null) }
    var deletingIdx by remember { mutableStateOf<Int?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    // 拉取该提供商的可用模型（null = 加载中，Result 承载成功/失败）
    var loadResult by remember(prov.id) { mutableStateOf<Result<List<ModelInfo>>?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(prov.id, prov.type, prov.baseUrl, prov.apiKey, reloadKey) {
        loadResult = null
        loadResult = runCatching { AndroidApiRuntime.models(prov) }
    }

    // 新增与编辑共用同一个底部面板；编辑目标按下标现取（列表随时可增删，不缓存 ModelInfo）
    val editing = editingIdx?.let { prov.models.getOrNull(it) }
    if (adding || editing != null) {
        ModelDetailSheet(
            model = editing ?: if (prov.type == "gradio") {
                ModelInfo(
                    inputModalities = listOf(Modality.TEXT),
                    outputModalities = listOf(Modality.IMAGE),
                    type = ModelType.IMAGE,
                )
            } else ModelInfo(),
            provider = prov,
            isNew = editing == null,
            onConfirm = { model ->
                val models = prov.models.toMutableList()
                // 手动添加了已存在的 ID 时覆盖原条目，避免同一模型出现两张卡片
                val idx = editingIdx ?: models.indexOfFirst { it.id == model.id }.takeIf { it >= 0 }
                if (idx != null) models[idx] = model else models.add(model)
                update(prov.copy(models = models))
                adding = false; editingIdx = null
            },
            onDismiss = { adding = false; editingIdx = null },
        )
    }

    deletingIdx?.let { idx ->
        AlertDialog(
            onDismissRequest = { deletingIdx = null },
            title = { Text(stringResource(R.string.delete_model_title)) },
            text = { Text(stringResource(R.string.delete_model_msg_fmt, prov.models[idx].name)) },
            confirmButton = {
                TextButton(onClick = {
                    update(prov.copy(models = prov.models.toMutableList().also { it.removeAt(idx) }))
                    deletingIdx = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingIdx = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showPicker) {
        ModelPickerSheet(
            prov = prov,
            loadResult = loadResult,
            onRetry = { reloadKey++ },
            update = update,
            onDismiss = { showPicker = false },
        )
    }

    Box(modifier.fillMaxSize()) {
        if (prov.models.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space4),
            ) {
                Text(stringResource(R.string.no_models),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.add_models_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = Space16),
                verticalArrangement = Arrangement.spacedBy(Space8),
            ) {
                itemsIndexed(prov.models) { idx, model ->
                    OutlinedCard(onClick = { editingIdx = idx }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Space16, vertical = Space8),
                            horizontalArrangement = Arrangement.spacedBy(Space16),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                ProviderIcon(model.id, size = 24.dp, modifier = Modifier.padding(6.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                                Text(model.name, style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space4),
                                    verticalArrangement = Arrangement.spacedBy(Space4)) {
                                    ModelCapabilityTags(model)
                                }
                            }
                            IconButton(onClick = { deletingIdx = idx }) {
                                Icon(Lucide.Trash2, stringResource(R.string.delete), Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Space16),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 6.dp,
        ) {
            Row(
                Modifier.padding(horizontal = Space8, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(Space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val available = loadResult?.getOrNull()?.size ?: 0
                if (prov.type != "gradio") {
                    BadgedBox(
                        modifier = Modifier.padding(end = Space8),
                        badge = { if (available > 0) Badge { Text(available.toString()) } },
                    ) {
                        IconButton(onClick = { showPicker = true }) {
                            Icon(Lucide.Package, stringResource(R.string.available_models),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Button(onClick = { adding = true }) {
                    Icon(Lucide.Plus, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Space8))
                    Text(stringResource(R.string.add_model))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelPickerSheet(
    prov: ApiProvider,
    loadResult: Result<List<ModelInfo>>?,
    onRetry: () -> Unit,
    update: (ApiProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }

    val allModels = loadResult?.getOrNull() ?: emptyList()
    val filtered = allModels.filter { filter.isBlank() || it.id.contains(filter.trim(), ignoreCase = true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = Space16).imePadding(),
            verticalArrangement = Arrangement.spacedBy(Space8),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.available_models), style = MaterialTheme.typography.titleMedium)
                val unselected = filtered.filter { m -> prov.models.none { it.id == m.id } }
                if (allModels.isNotEmpty()) {
                    TextButton(onClick = {
                        if (unselected.isNotEmpty()) {
                            update(prov.copy(models = prov.models + unselected))
                        } else {
                            val ids = filtered.map { it.id }.toSet()
                            update(prov.copy(models = prov.models.filter { it.id !in ids }))
                        }
                    }) {
                        Text(if (unselected.isNotEmpty()) stringResource(R.string.add_all_fmt, unselected.size)
                             else stringResource(R.string.remove_all))
                    }
                }
            }

            when {
                loadResult == null -> {
                    Column(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(Space12))
                        Text(stringResource(R.string.loading_models),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                loadResult.isFailure -> {
                    Column(
                        Modifier.fillMaxWidth().weight(1f).padding(Space16),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(stringResource(R.string.models_load_failed),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(Space8))
                        Text(loadResult.exceptionOrNull()?.message ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(Space12))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    }
                }
                else -> {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Space8),
                    ) {
                        itemsIndexed(filtered) { _, model ->
                            val selected = prov.models.any { it.id == model.id }
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(horizontal = Space12, vertical = Space8),
                                horizontalArrangement = Arrangement.spacedBy(Space12),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ProviderIcon(model.id, size = 32.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                                    Text(model.id, style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space4),
                                        verticalArrangement = Arrangement.spacedBy(Space4)) {
                                        ModelCapabilityTags(model)
                                    }
                                }
                                IconButton(onClick = {
                                    if (selected) update(prov.copy(models = prov.models.filter { it.id != model.id }))
                                    else update(prov.copy(models = prov.models + model))
                                }) {
                                    Icon(if (selected) Lucide.X else Lucide.Plus, null, Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            OutlinedTextField(filter, { filter = it },
                label = { Text(stringResource(R.string.filter_models)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
