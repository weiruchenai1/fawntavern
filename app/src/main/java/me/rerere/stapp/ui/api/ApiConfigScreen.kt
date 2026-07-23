package me.rerere.stapp.ui.api

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.isSystemInDarkTheme
import me.rerere.stapp.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import me.rerere.stapp.data.api.ApiConfigStore
import me.rerere.stapp.data.api.ApiProvider
import me.rerere.stapp.data.api.ConnectionTester
import me.rerere.stapp.data.api.ModelApi
import kotlinx.coroutines.launch
import me.rerere.stapp.ui.components.AppTopBar
import me.rerere.stapp.ui.components.AppIconButton
import me.rerere.stapp.ui.components.draggableLiftScale
import sh.calvin.reorderable.ReorderableItem
import me.rerere.stapp.ui.components.rememberReorderableList
import me.rerere.stapp.ui.components.ConfirmDeleteDialog
import me.rerere.stapp.ui.components.ModelPickerList
import me.rerere.stapp.ui.components.Space4
import me.rerere.stapp.ui.components.Space8
import me.rerere.stapp.ui.components.Space12
import me.rerere.stapp.ui.components.Space16
import me.rerere.stapp.ui.chat.ModelPickerSheet

val API_TYPES = listOf("openai" to "OpenAI", "google" to "Google", "claude" to "Claude")

@Composable
fun ApiConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(ApiConfigStore.loadConfig(context)) }
    fun save() { ApiConfigStore.saveConfig(context, config) }

    var editingId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    // SaveableStateHolder：进入详情页时列表离开组合，其 LazyListState 被暂存；
    // 返回时恢复，避免列表滚动位置丢失（跳回顶部）。
    val stateHolder = rememberSaveableStateHolder()

    if (editingId != null) {
        stateHolder.SaveableStateProvider("detail") {
            val prov = config.providers.find { it.id == editingId } ?: return@SaveableStateProvider
            BackHandler { editingId = null }
            ProviderDetailScreen(
                provider = prov,
                onBack = { editingId = null },
                onSave = { updated ->
                    config = config.copy(providers = config.providers.map { if (it.id == updated.id) updated else it })
                    save()
                    Toast.makeText(context, context.getString(R.string.saved), Toast.LENGTH_SHORT).show()
                },
                onDelete = {
                    config = config.copy(providers = config.providers.filter { it.id != prov.id })
                    save()
                    editingId = null
                },
                onChange = { updated ->
                    config = config.copy(providers = config.providers.map { if (it.id == updated.id) updated else it })
                    save()
                },
            )
        }
        return
    }

    if (adding) {
        stateHolder.SaveableStateProvider("detail") {
            ProviderDetailScreen(
                provider = ApiProvider(),
                onBack = { adding = false },
                onSave = { newProv ->
                    config = config.copy(providers = config.providers + newProv)
                    save()
                    Toast.makeText(context, context.getString(R.string.saved), Toast.LENGTH_SHORT).show()
                    adding = false
                    editingId = newProv.id
                },
                onDelete = { adding = false }
            )
        }
        return
    }

    stateHolder.SaveableStateProvider("list") {
        BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(stringResource(R.string.api_config), onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Lucide.Plus, stringResource(R.string.add_provider), Modifier.size(24.dp))
            }
        }
    ) { padding ->
        if (config.providers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_api_providers), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // 长按 grip 手柄拖动排序，靠近边缘自动滚动（sh.calvin.reorderable）
            val (listState, reorderState) = rememberReorderableList(
                items = config.providers,
                keyOf = { it.id },
            ) { list ->
                config = config.copy(providers = list)
                save()
            }

            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(Space12),
            ) {
                itemsIndexed(config.providers, key = { _, p -> p.id }) { _, prov ->
                    ReorderableItem(reorderState, key = prov.id) { dragging ->
                        ProviderCard(
                            prov = prov,
                            onClick = { editingId = prov.id },
                            dragging = dragging,
                            handleModifier = Modifier.longPressDraggableHandle(),
                        )
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
    } // SaveableStateProvider("list")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    prov: ApiProvider,
    onClick: () -> Unit,
    dragging: Boolean = false,
    handleModifier: Modifier = Modifier,
) {
    Row(
        Modifier.fillMaxWidth()
            .draggableLiftScale(dragging)
            .clip(RoundedCornerShape(16.dp))
            .background(if (prov.enabled) MaterialTheme.colorScheme.surfaceContainer
                        else MaterialTheme.colorScheme.errorContainer)
            .clickable { onClick() }
            .padding(horizontal = Space16, vertical = Space12),
        horizontalArrangement = Arrangement.spacedBy(Space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderIcon(prov.name, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
            Text(prov.name, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (prov.baseUrl.isNotBlank()) {
                Text(prov.baseUrl, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space4),
                verticalArrangement = Arrangement.spacedBy(Space4)) {
                Tag(type = if (prov.enabled) TagType.SUCCESS else TagType.WARNING) {
                    Text(if (prov.enabled) stringResource(R.string.enabled_label)
                         else stringResource(R.string.disabled_label))
                }
                Tag(type = TagType.INFO) {
                    Text(stringResource(R.string.models_count_fmt, prov.models.size))
                }
            }
        }
        // 拖动手柄：长按后上下拖拽排序
        Icon(
            Lucide.GripVertical, stringResource(R.string.reorder),
            Modifier.size(24.dp).then(handleModifier),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderDetailScreen(
    provider: ApiProvider,
    onBack: () -> Unit,
    onSave: (ApiProvider) -> Unit,
    onDelete: () -> Unit,
    onChange: (ApiProvider) -> Unit = {},
) {
    var prov by remember { mutableStateOf(provider) }
    var tab by remember { mutableIntStateOf(0) }
    val isNew = provider.name.isBlank()

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIconButton(
                    icon = Lucide.ChevronLeft,
                    contentDescription = stringResource(R.string.back),
                    onClick = onBack,
                    container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    size = 32.dp,
                    iconSize = 24.dp,
                )
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space8)) {
                    if (!isNew) { ProviderIcon(prov.name, size = 24.dp) }
                    Text(if (isNew) stringResource(R.string.new_provider) else prov.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(32.dp))
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Lucide.Settings, null) },
                    label = { Text(stringResource(R.string.config_tab)) })
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Lucide.Package, null) },
                    label = { Text(stringResource(R.string.models_tab)) })
            }
        }
    ) { padding ->
        when (tab) {
            // 配置项仅改动草稿（prov），点“保存”才落盘；模型页无保存按钮，改动即时落盘
            0 -> ProviderConfigTab(prov, { prov = it }, onSave, onDelete, isNew, Modifier.padding(padding))
            1 -> ProviderModelTab(prov, { prov = it; onChange(it) }, Modifier.padding(padding))
        }
    }
}

@Composable
private fun ProviderConfigTab(
    prov: ApiProvider,
    update: (ApiProvider) -> Unit,
    onSave: (ApiProvider) -> Unit,
    onDelete: () -> Unit,
    isNew: Boolean,
    modifier: Modifier = Modifier,
) {
    var keyVisible by remember { mutableStateOf(false) }
    var name by remember(prov) { mutableStateOf(prov.name) }
    var baseUrl by remember(prov) { mutableStateOf(prov.baseUrl) }
    var apiKey by remember(prov) { mutableStateOf(prov.apiKey) }
    var apiPath by remember(prov) { mutableStateOf("/chat/completions") }
    var enabledValue by remember(prov) { mutableStateOf(prov.enabled) }
    var responseApi by remember { mutableStateOf(false) }
    var balanceEnabled by remember(prov) { mutableStateOf(prov.balanceEnabled) }
    var balancePath by remember(prov) { mutableStateOf(prov.balancePath) }
    var balanceJsonKey by remember(prov) { mutableStateOf(prov.balanceJsonKey) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showTestDialog by remember { mutableStateOf(false) }

    val currentProv = prov.copy(
        name = name, baseUrl = baseUrl, apiKey = apiKey, enabled = enabledValue,
        balanceEnabled = balanceEnabled, balancePath = balancePath, balanceJsonKey = balanceJsonKey,
    )

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space16),
        verticalArrangement = Arrangement.spacedBy(Space16),
    ) {
        val types = API_TYPES
        val typeIdx = types.indexOfFirst { it.first == prov.type }.coerceAtLeast(0)
        var selectedTypeIdx by remember(prov) { mutableIntStateOf(typeIdx) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            types.forEachIndexed { idx, (key, label) ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(idx, types.size),
                    selected = selectedTypeIdx == idx,
                    onClick = { selectedTypeIdx = idx; update(prov.copy(type = key)) },
                    label = { Text(label) })
            }
        }

        OutlinedTextField(name, { name = it; update(currentProv.copy(name = it)) },
            label = { Text(stringResource(R.string.provider_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(apiKey, { apiKey = it; update(currentProv.copy(apiKey = it)) },
            label = { Text(stringResource(R.string.api_key_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(if (keyVisible) Lucide.EyeOff else Lucide.Eye, null, Modifier.size(20.dp))
                }
            })

        OutlinedTextField(baseUrl, { baseUrl = it; update(currentProv.copy(baseUrl = it)) },
            label = { Text(stringResource(R.string.api_base_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.openai.com/v1") })

        OutlinedTextField(apiPath, { apiPath = it },
            label = { Text(stringResource(R.string.api_path_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.is_enabled_label), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(enabledValue, { enabledValue = it; update(currentProv.copy(enabled = it)) })
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.response_api_label), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(responseApi, { responseApi = it })
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.balance_label), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(balanceEnabled, { balanceEnabled = it; update(currentProv.copy(balanceEnabled = it)) })
        }

        if (balanceEnabled) {
            OutlinedTextField(balancePath, { balancePath = it; update(currentProv.copy(balancePath = it)) },
                label = { Text(stringResource(R.string.balance_api_path)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("/user/balance") })

            OutlinedTextField(balanceJsonKey, { balanceJsonKey = it; update(currentProv.copy(balanceJsonKey = it)) },
                label = { Text(stringResource(R.string.balance_json_key)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("balance_infos[0].total_balance") })
        }

        Spacer(Modifier.height(Space8))

        if (showTestDialog) {
            // 用当前编辑中的配置（含未保存改动）测试，模型取自 currentProv.models
            ConnectionTestDialog(prov = currentProv, onDismiss = { showTestDialog = false })
        }

        if (showDeleteConfirm) {
            ConfirmDeleteDialog(
                title = stringResource(R.string.delete_provider_title),
                text = stringResource(R.string.delete_provider_msg_fmt, currentProv.name),
                onConfirm = { showDeleteConfirm = false; onDelete() },
                onDismiss = { showDeleteConfirm = false },
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space8),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { showTestDialog = true }) {
                Icon(Lucide.PlugZap, stringResource(R.string.test_connection), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            if (!isNew) {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Lucide.Trash2, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                }
            }
            Button(onClick = { onSave(currentProv) }) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable
private fun ProviderModelTab(prov: ApiProvider, update: (ApiProvider) -> Unit, modifier: Modifier = Modifier) {
    var adding by remember { mutableStateOf(false) }
    var editingIdx by remember { mutableStateOf<Int?>(null) }
    var deletingIdx by remember { mutableStateOf<Int?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    // 拉取该提供商的可用模型（null = 加载中，Result 承载成功/失败）
    var loadResult by remember(prov.id) { mutableStateOf<Result<List<String>>?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(prov.id, prov.type, prov.baseUrl, prov.apiKey, reloadKey) {
        loadResult = null
        loadResult = runCatching { ModelApi.listModels(prov) }
    }

    if (adding || editingIdx != null) {
        val idx = editingIdx
        val initial = if (idx != null) prov.models.getOrElse(idx) { "" } else ""
        var modelName by remember { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { adding = false; editingIdx = null },
            title = { Text(if (idx != null) stringResource(R.string.edit_model) else stringResource(R.string.add_model)) },
            text = {
                OutlinedTextField(modelName, { modelName = it },
                    label = { Text(stringResource(R.string.model_id_label)) }, singleLine = true,
                    placeholder = { Text("gpt-4o") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = modelName.trim()
                    if (trimmed.isNotBlank()) {
                        val models = prov.models.toMutableList()
                        if (idx != null) models[idx] = trimmed else models.add(trimmed)
                        update(prov.copy(models = models))
                    }
                    adding = false; editingIdx = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { adding = false; editingIdx = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    deletingIdx?.let { idx ->
        AlertDialog(
            onDismissRequest = { deletingIdx = null },
            title = { Text(stringResource(R.string.delete_model_title)) },
            text = { Text(stringResource(R.string.delete_model_msg_fmt, prov.models[idx])) },
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
                verticalArrangement = Arrangement.spacedBy(Space8),
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                itemsIndexed(prov.models) { idx, model ->
                    OutlinedCard(onClick = { editingIdx = idx }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Space16, vertical = Space8),
                            horizontalArrangement = Arrangement.spacedBy(Space16),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                ProviderIcon(model, size = 24.dp, modifier = Modifier.padding(6.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                                Text(model, style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(Space4)) {
                                    Tag(type = TagType.INFO) {
                                        Text(stringResource(R.string.chat_model_label))
                                    }
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
                BadgedBox(
                    modifier = Modifier.padding(end = Space8),
                    badge = { if (available > 0) Badge { Text(available.toString()) } },
                ) {
                    IconButton(onClick = { showPicker = true }) {
                        Icon(Lucide.Package, stringResource(R.string.available_models),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    prov: ApiProvider,
    loadResult: Result<List<String>>?,
    onRetry: () -> Unit,
    update: (ApiProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }

    val allModels = loadResult?.getOrNull() ?: emptyList()
    val filtered = allModels.filter { filter.isBlank() || it.contains(filter.trim(), ignoreCase = true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
                val unselected = filtered.count { it !in prov.models }
                if (allModels.isNotEmpty()) {
                    TextButton(onClick = {
                        if (unselected > 0) {
                            update(prov.copy(models = prov.models + filtered.filter { it !in prov.models }))
                        } else {
                            update(prov.copy(models = prov.models.filter { it !in filtered }))
                        }
                    }) {
                        Text(if (unselected > 0) stringResource(R.string.add_all_fmt, unselected)
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
                loadResult?.isFailure == true -> {
                    Column(
                        Modifier.fillMaxWidth().weight(1f).padding(Space16),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(stringResource(R.string.models_load_failed),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(Space8))
                        Text(loadResult?.exceptionOrNull()?.message ?: "",
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
                            val selected = model in prov.models
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(horizontal = Space12, vertical = Space8),
                                horizontalArrangement = Arrangement.spacedBy(Space12),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ProviderIcon(model, size = 32.dp)
                                Text(model, style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = {
                                    if (selected) update(prov.copy(models = prov.models - model))
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

/** 单项连接测试的状态 */
private sealed interface TestState {
    data object Idle : TestState
    data object Loading : TestState
    data class Ok(val text: String) : TestState
    data class Err(val message: String) : TestState
}

/**
 * 连接测试对话框：选一个模型，对其并发跑 非流式 / 流式 / 工具调用 三项测试，
 * 各自独立显示进度与结果（成功显示回复文本，失败显示错误信息）。
 * 模型选择通过底部面板（[ModelPickerSheet]）完成。
 */
@Composable
private fun ConnectionTestDialog(prov: ApiProvider, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedModel by remember(prov.id) { mutableStateOf(prov.models.firstOrNull() ?: "") }
    var showModelPicker by remember { mutableStateOf(false) }
    var nonStreaming by remember { mutableStateOf<TestState>(TestState.Idle) }
    var streaming by remember { mutableStateOf<TestState>(TestState.Idle) }
    var streamingText by remember { mutableStateOf("") }
    var toolCall by remember { mutableStateOf<TestState>(TestState.Idle) }

    val running = nonStreaming is TestState.Loading ||
        streaming is TestState.Loading || toolCall is TestState.Loading

    fun runTests() {
        if (selectedModel.isBlank()) return
        nonStreaming = TestState.Loading
        streaming = TestState.Loading
        streamingText = ""
        toolCall = TestState.Loading
        scope.launch {
            nonStreaming = runCatching { TestState.Ok(ConnectionTester.testNonStreaming(prov, selectedModel)) }
                .getOrElse { TestState.Err(it.message ?: it.toString()) }
        }
        scope.launch {
            streaming = runCatching {
                ConnectionTester.testStreaming(prov, selectedModel) { streamingText += it }
                TestState.Ok(streamingText)
            }.getOrElse { TestState.Err(it.message ?: it.toString()) }
        }
        scope.launch {
            toolCall = runCatching {
                val r = ConnectionTester.testToolCall(prov, selectedModel)
                TestState.Ok(
                    if (r.toolName.isNotBlank())
                        context.getString(R.string.test_tool_called_fmt, r.toolName, r.args)
                    else context.getString(R.string.test_tool_not_called_fmt, r.text)
                )
            }.getOrElse { TestState.Err(it.message ?: it.toString()) }
        }
    }

    // 模型选择底板：从屏幕底部滑出，点击模型后自动关闭并把选中模型带回弹窗
    if (showModelPicker) {
        ModelPickerSheet(
            providers = listOf(prov),
            currentModel = "${prov.id}::$selectedModel",
            onSelect = { _, modelId -> selectedModel = modelId; showModelPicker = false },
            onDismiss = { showModelPicker = false },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.test_connection)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space12)) {
                // 已选模型：点击可重新打开底板换模型
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { showModelPicker = true }
                        .padding(horizontal = Space12, vertical = Space8),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space8),
                ) {
                    ProviderIcon(selectedModel.ifBlank { prov.name }, size = 24.dp)
                    Text(
                        text = selectedModel.ifBlank { stringResource(R.string.select_model_hint) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedModel.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Lucide.ChevronDown, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TestResultRow(stringResource(R.string.test_non_streaming), nonStreaming)
                TestResultRow(stringResource(R.string.test_streaming), streaming, liveText = streamingText)
                TestResultRow(stringResource(R.string.test_tool_call), toolCall)
            }
        },
        confirmButton = {
            TextButton(onClick = { runTests() }, enabled = !running && selectedModel.isNotBlank()) {
                Text(if (running) stringResource(R.string.testing) else stringResource(R.string.start_test))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun TestResultRow(label: String, state: TestState, liveText: String = "") {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Space8),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
        when (state) {
            TestState.Idle -> Text("—", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TestState.Loading -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (liveText.isNotBlank()) {
                    Text(liveText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            is TestState.Ok -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                Text("✓", style = MaterialTheme.typography.titleMedium, color = successGreen())
                val shown = liveText.ifBlank { state.text }
                if (shown.isNotBlank()) {
                    Text(shown, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            is TestState.Err -> Text(state.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f),
                maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun successGreen(): Color =
    if (isSystemInDarkTheme()) Color(0xFF86EFAC) else Color(0xFF166534)
