package me.rerere.fawntavern.ui.api

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.SelectionContainer
import me.rerere.fawntavern.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ConnectionTester
import me.rerere.fawntavern.data.api.ModelApi
import me.rerere.fawntavern.data.api.ModelInfo
import me.rerere.fawntavern.data.api.ModelType
import me.rerere.fawntavern.data.api.Modality
import me.rerere.fawntavern.data.api.modelInfoOf
import kotlinx.coroutines.launch
import me.rerere.fawntavern.ui.components.AppTopBar
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.draggableLiftScale
import sh.calvin.reorderable.ReorderableItem
import me.rerere.fawntavern.ui.components.rememberReorderableList
import me.rerere.fawntavern.ui.components.ConfirmDeleteDialog
import me.rerere.fawntavern.ui.components.ModelSelectorSheet
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.components.rememberModelSelectorState

val API_TYPES = listOf(
    "openai" to "OpenAI",
    "google" to "Google",
    "claude" to "Claude",
    "gradio" to "Gradio",
)

private const val HF_Z_IMAGE_URL = "https://mrfakename-z-image-turbo.hf.space"

private fun huggingFaceImageTemplate() = ApiProvider(
    name = "Hugging Face Space",
    type = "gradio",
    baseUrl = HF_Z_IMAGE_URL,
    apiPath = "/generate_image",
    models = listOf(
        ModelInfo(
            id = "z-image-turbo",
            displayName = "Z-Image Turbo",
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.IMAGE),
            type = ModelType.IMAGE,
        ),
    ),
)

@Composable
fun ApiConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val controller = remember(context) { ApiConfigController(AndroidApiConfigDataSource(context)) }
    val initial = remember(controller) { controller.load() }
    var config by remember(controller) { mutableStateOf(initial.config) }
    val configWasRecovered = initial.recovered
    val configRecoveredMessage = stringResource(R.string.api_config_recovered)
    LaunchedEffect(configWasRecovered) {
        if (configWasRecovered) {
            Toast.makeText(context, configRecoveredMessage, Toast.LENGTH_LONG).show()
        }
    }
    // 每次落盘都校正选中模型：禁用/删除提供商、移除模型后不留悬空选择
    fun save() {
        config = controller.save(config)
    }

    var editingId by remember { mutableStateOf<String?>(null) }
    var addingProvider by remember { mutableStateOf<ApiProvider?>(null) }
    var selectedModelType by rememberSaveable { mutableStateOf(ModelType.CHAT) }
    // SaveableStateHolder：进入详情页时列表离开组合，其 LazyListState 被暂存；
    // 返回时恢复，避免列表滚动位置丢失（跳回顶部）。
    val stateHolder = rememberSaveableStateHolder()

    if (editingId != null) {
        stateHolder.SaveableStateProvider("detail") {
            val prov = config.providers.find { it.id == editingId } ?: return@SaveableStateProvider
            BackHandler { editingId = null }
            ProviderDetailScreen(
                provider = prov,
                isNew = false,
                onBack = { editingId = null },
                onSave = { updated ->
                    config = config.copy(providers = config.providers.map { if (it.id == updated.id) updated else it })
                    save()
                    Toast.makeText(context, resources.getString(R.string.saved), Toast.LENGTH_SHORT).show()
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

    addingProvider?.let { draftProvider ->
        stateHolder.SaveableStateProvider("detail") {
            ProviderDetailScreen(
                provider = draftProvider,
                isNew = true,
                onBack = { addingProvider = null },
                onSave = { newProv ->
                    config = config.copy(providers = config.providers + newProv)
                    save()
                    Toast.makeText(context, resources.getString(R.string.saved), Toast.LENGTH_SHORT).show()
                    addingProvider = null
                    editingId = newProv.id
                },
                onDelete = { addingProvider = null }
            )
        }
        return
    }

    stateHolder.SaveableStateProvider("list") {
        BackHandler(onBack = onBack)

    val visibleProviders = config.providers.filter { provider ->
        when (selectedModelType) {
            ModelType.CHAT -> provider.type != "gradio" && (
                provider.models.isEmpty() || provider.models.any { it.type == ModelType.CHAT }
            )
            ModelType.IMAGE -> provider.type == "gradio" || provider.models.any { it.type == ModelType.IMAGE }
            ModelType.VIDEO -> provider.models.any { it.type == ModelType.VIDEO }
        }
    }
    val configuredHf = config.providers.firstOrNull {
        it.type == "gradio" && it.models.any { model -> model.id == "z-image-turbo" }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                AppTopBar(stringResource(R.string.api_config), onBack)
                PrimaryTabRow(
                    selectedTabIndex = if (selectedModelType == ModelType.CHAT) 0 else 1,
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    listOf(ModelType.CHAT, ModelType.IMAGE).forEach { type ->
                        Tab(
                            selected = selectedModelType == type,
                            onClick = { selectedModelType = type },
                            text = {
                                Text(stringResource(
                                    if (type == ModelType.CHAT) R.string.chat_models_tab
                                    else R.string.image_models_tab,
                                ))
                            },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (selectedModelType == ModelType.IMAGE) {
                    if (configuredHf != null) editingId = configuredHf.id
                    else addingProvider = huggingFaceImageTemplate()
                } else {
                    addingProvider = ApiProvider()
                }
            }) {
                Icon(Lucide.Plus, stringResource(R.string.add_provider), Modifier.size(24.dp))
            }
        }
    ) { padding ->
        // Tab 只是同一份 provider 配置的任务视图；混合提供商仍共享地址和密钥。
        val (listState, reorderState) = rememberReorderableList(
            items = visibleProviders,
            keyOf = { it.id },
        ) { list ->
            config = config.copy(providers = replaceVisibleProviderOrder(config.providers, list))
            save()
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(Space12),
        ) {
            if (selectedModelType == ModelType.IMAGE && configuredHf == null) {
                item(key = "hf-z-image-template") {
                    HuggingFaceTemplateCard(
                        onClick = { addingProvider = huggingFaceImageTemplate() },
                    )
                }
            }
            if (visibleProviders.isEmpty() && selectedModelType == ModelType.CHAT) {
                item {
                    Box(Modifier.fillParentMaxHeight(0.7f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_api_providers), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            itemsIndexed(visibleProviders, key = { _, p -> p.id }) { _, prov ->
                ReorderableItem(reorderState, key = prov.id) { dragging ->
                    ProviderCard(
                        prov = prov,
                        modelCount = prov.models.count { it.type == selectedModelType },
                        onClick = { editingId = prov.id },
                        dragging = dragging,
                        modifier = Modifier.longPressDraggableHandle(),
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    } // SaveableStateProvider("list")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    prov: ApiProvider,
    modelCount: Int = prov.models.size,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth()
            .draggableLiftScale(dragging)
            .clip(RoundedCornerShape(12.dp))
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
                    Text(androidx.compose.ui.res.pluralStringResource(R.plurals.models_count_fmt, modelCount, modelCount))
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

@Composable
private fun HuggingFaceTemplateCard(onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space16, vertical = Space12),
            horizontalArrangement = Arrangement.spacedBy(Space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderIcon("Hugging Face", size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                Text(stringResource(R.string.hf_space_name), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.z_image_turbo_name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.hf_space_template_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Tag(type = TagType.INFO) {
                Text(stringResource(R.string.template_label))
            }
        }
    }
}

internal fun replaceVisibleProviderOrder(
    all: List<ApiProvider>,
    reordered: List<ApiProvider>,
): List<ApiProvider> {
    val ids = reordered.mapTo(hashSetOf()) { it.id }
    val iterator = reordered.iterator()
    return all.map { provider -> if (provider.id in ids) iterator.next() else provider }
}

@Composable
private fun ProviderDetailScreen(
    provider: ApiProvider,
    isNew: Boolean,
    onBack: () -> Unit,
    onSave: (ApiProvider) -> Unit,
    onDelete: () -> Unit,
    onChange: (ApiProvider) -> Unit = {},
) {
    var prov by remember { mutableStateOf(provider) }
    val pagerState = key(provider.id) { rememberPagerState(pageCount = { 2 }) }
    val scope = rememberCoroutineScope()

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
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(Lucide.Settings, null) },
                    label = { Text(stringResource(R.string.config_tab)) })
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(Lucide.Package, null) },
                    label = { Text(stringResource(R.string.models_tab)) })
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            key = { it },
        ) { page ->
            when (page) {
                // 配置项仅改动草稿（prov），点“保存”才落盘；模型页无保存按钮，改动即时落盘
                0 -> ProviderConfigTab(prov, { prov = it }, onSave, onDelete, isNew)
                1 -> ProviderModelTab(prov, { prov = it; onChange(it) })
            }
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
    var customApiPath by remember(prov) { mutableStateOf(prov.apiPath) }
    var chatApiPath by remember(prov) {
        mutableStateOf(if (prov.type == "openai") {
            prov.chatApiPath.ifBlank { "/chat/completions" }
        } else prov.chatApiPath)
    }
    var responsesApiPath by remember(prov) {
        mutableStateOf(if (prov.type == "openai") {
            prov.responsesApiPath.ifBlank { "/responses" }
        } else prov.responsesApiPath)
    }
    var imageGenerationApiPath by remember(prov) {
        mutableStateOf(if (prov.type == "openai") {
            prov.imageGenerationApiPath.ifBlank { "/images/generations" }
        } else prov.imageGenerationApiPath)
    }
    var imageEditApiPath by remember(prov) {
        mutableStateOf(if (prov.type == "openai") {
            prov.imageEditApiPath.ifBlank { "/images/edits" }
        } else prov.imageEditApiPath)
    }
    var apiKey by remember(prov) { mutableStateOf(prov.apiKey) }
    var enabledValue by remember(prov) { mutableStateOf(prov.enabled) }
    var responseApi by remember(prov) { mutableStateOf(prov.useResponseApi) }
    var balanceEnabled by remember(prov) { mutableStateOf(prov.balanceEnabled) }
    var balancePath by remember(prov) { mutableStateOf(prov.balancePath) }
    var balanceJsonKey by remember(prov) { mutableStateOf(prov.balanceJsonKey) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showTestDialog by remember { mutableStateOf(false) }

    val currentProv = prov.copy(
        name = name,
        baseUrl = baseUrl,
        apiPath = if (prov.type == "openai") "" else customApiPath,
        chatApiPath = chatApiPath,
        responsesApiPath = responsesApiPath,
        imageGenerationApiPath = imageGenerationApiPath,
        imageEditApiPath = imageEditApiPath,
        apiKey = apiKey, enabled = enabledValue,
        useResponseApi = responseApi,
        balanceEnabled = balanceEnabled, balancePath = balancePath, balanceJsonKey = balanceJsonKey,
    )
    val apiPath = when (prov.type) {
        "google" -> customApiPath.ifBlank {
            "/models/{model}:streamGenerateContent?alt=sse"
        }
        "claude" -> customApiPath.ifBlank { "/messages" }
        "gradio" -> customApiPath.ifBlank { "/generate_image" }
        else -> customApiPath
    }

    Column(
        modifier.fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(Space16),
        verticalArrangement = Arrangement.spacedBy(Space16),
    ) {
        val types = API_TYPES
        val typeIdx = types.indexOfFirst { it.first == prov.type }.coerceAtLeast(0)
        var selectedTypeIdx by remember(prov) { mutableIntStateOf(typeIdx) }
        val typeScrollState = rememberScrollState()
        val typeItemWidthPx = with(LocalDensity.current) { 104.dp.roundToPx() }
        LaunchedEffect(selectedTypeIdx, typeItemWidthPx) {
            typeScrollState.animateScrollTo(selectedTypeIdx * typeItemWidthPx)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(typeScrollState),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.width((types.size * 104).dp)) {
                types.forEachIndexed { idx, (key, label) ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(idx, types.size),
                        selected = selectedTypeIdx == idx,
                        onClick = {
                            selectedTypeIdx = idx
                            if (key != "openai") responseApi = false
                            customApiPath = ""
                            chatApiPath = ""
                            responsesApiPath = ""
                            imageGenerationApiPath = ""
                            imageEditApiPath = ""
                            update(currentProv.copy(
                                type = key,
                                apiPath = "",
                                chatApiPath = "",
                                responsesApiPath = "",
                                imageGenerationApiPath = "",
                                imageEditApiPath = "",
                                useResponseApi = key == "openai" && responseApi,
                            ))
                        },
                        label = { Text(label) },
                    )
                }
            }
        }

        OutlinedTextField(name, { name = it; update(currentProv.copy(name = it)) },
            label = { Text(stringResource(R.string.provider_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(apiKey, { apiKey = it; update(currentProv.copy(apiKey = it)) },
            label = { Text(stringResource(
                if (prov.type == "gradio") R.string.hf_token_label else R.string.api_key_label,
            )) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(if (keyVisible) Lucide.EyeOff else Lucide.Eye, null, Modifier.size(20.dp))
                }
            })

        OutlinedTextField(baseUrl, { baseUrl = it; update(currentProv.copy(baseUrl = it)) },
            label = { Text(stringResource(R.string.api_base_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (prov.type == "gradio") HF_Z_IMAGE_URL else "https://api.openai.com/v1") })

        if (prov.type == "gradio") {
            Text(
                stringResource(R.string.hf_token_optional_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (prov.type == "openai") {
            OutlinedTextField(
                value = chatApiPath,
                onValueChange = { chatApiPath = it; update(currentProv.copy(chatApiPath = it)) },
                label = { Text(stringResource(R.string.chat_api_path_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = responsesApiPath,
                onValueChange = { responsesApiPath = it; update(currentProv.copy(responsesApiPath = it)) },
                label = { Text(stringResource(R.string.responses_api_path_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = imageGenerationApiPath,
                onValueChange = {
                    imageGenerationApiPath = it
                    update(currentProv.copy(imageGenerationApiPath = it))
                },
                label = { Text(stringResource(R.string.image_generation_api_path_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = imageEditApiPath,
                onValueChange = { imageEditApiPath = it; update(currentProv.copy(imageEditApiPath = it)) },
                label = { Text(stringResource(R.string.image_edit_api_path_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = apiPath,
                onValueChange = {
                    customApiPath = it
                    update(currentProv.copy(apiPath = it))
                },
                label = { Text(stringResource(R.string.api_path_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.is_enabled_label), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(enabledValue, { enabledValue = it; update(currentProv.copy(enabled = it)) })
        }

        if (prov.type == "openai") {
            Column(verticalArrangement = Arrangement.spacedBy(Space4)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.response_api_label), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(responseApi, {
                        responseApi = it
                        update(currentProv.copy(useResponseApi = it))
                    })
                }
                Text(
                    stringResource(R.string.response_api_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (prov.type != "gradio") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.balance_label), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(balanceEnabled, { balanceEnabled = it; update(currentProv.copy(balanceEnabled = it)) })
            }
        }

        if (balanceEnabled && prov.type != "gradio") {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderModelTab(prov: ApiProvider, update: (ApiProvider) -> Unit, modifier: Modifier = Modifier) {
    var adding by remember { mutableStateOf(false) }
    var editingIdx by remember { mutableStateOf<Int?>(null) }
    var deletingIdx by remember { mutableStateOf<Int?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    // 拉取该提供商的可用模型（null = 加载中，Result 承载成功/失败）
    var loadResult by remember(prov.id) { mutableStateOf<Result<List<ModelInfo>>?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(prov.id, prov.type, prov.baseUrl, prov.apiKey, reloadKey) {
        loadResult = null
        loadResult = runCatching { ModelApi.listModels(prov) }
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

/** 单项连接测试的状态 */
private sealed interface TestState {
    data object Idle : TestState
    data object Loading : TestState
    data class Ok(val text: String) : TestState
    data class Err(val message: String) : TestState
}

private enum class ConnectionTestKind { NON_STREAMING, STREAMING, TOOL_CALL }

/**
 * 连接测试对话框：选一个模型，对其并发跑 非流式 / 流式 / 工具调用 三项测试，
 * 各自独立显示进度与结果（成功显示回复文本，失败显示错误信息）。
 * 模型选择通过底部面板（[ModelPickerSheet]）完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionTestDialog(prov: ApiProvider, onDismiss: () -> Unit) {
    if (prov.type == "gradio") {
        GradioConnectionTestDialog(prov, onDismiss)
        return
    }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    var selectedModel by remember(prov.id) { mutableStateOf(prov.models.firstOrNull()?.id ?: "") }
    // filterEnabled=false —— 提供商还没启用时也要能先测通
    // currentModel 须带 "providerId::" 前缀，行内才按此匹配选中态
    val modelSelector = rememberModelSelectorState("${prov.id}::$selectedModel", listOf(prov), filterEnabled = false)
    var nonStreaming by remember { mutableStateOf<TestState>(TestState.Idle) }
    var streaming by remember { mutableStateOf<TestState>(TestState.Idle) }
    var streamingText by remember { mutableStateOf("") }
    var toolCall by remember { mutableStateOf<TestState>(TestState.Idle) }
    var detailKind by remember { mutableStateOf<ConnectionTestKind?>(null) }

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
                        resources.getString(R.string.test_tool_called_fmt, r.toolName, r.args)
                    else resources.getString(R.string.test_tool_not_called_fmt, r.text)
                )
            }.getOrElse { TestState.Err(it.message ?: it.toString()) }
        }
    }

    // 模型选择底板：从屏幕底部滑出，点击模型后自动关闭并把选中模型带回弹窗
    ModelSelectorSheet(
        state = modelSelector,
        onSelect = { _, modelId ->
            selectedModel = modelId
            nonStreaming = TestState.Idle
            streaming = TestState.Idle
            streamingText = ""
            toolCall = TestState.Idle
            detailKind = null
        },
    )

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
                        .clickable { modelSelector.open() }
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
                TestResultRow(
                    stringResource(R.string.test_non_streaming),
                    nonStreaming,
                    onClick = { detailKind = ConnectionTestKind.NON_STREAMING },
                )
                TestResultRow(
                    stringResource(R.string.test_streaming),
                    streaming,
                    liveText = streamingText,
                    onClick = { detailKind = ConnectionTestKind.STREAMING },
                )
                TestResultRow(
                    stringResource(R.string.test_tool_call),
                    toolCall,
                    onClick = { detailKind = ConnectionTestKind.TOOL_CALL },
                )
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

    detailKind?.let { kind ->
        val label = when (kind) {
            ConnectionTestKind.NON_STREAMING -> stringResource(R.string.test_non_streaming)
            ConnectionTestKind.STREAMING -> stringResource(R.string.test_streaming)
            ConnectionTestKind.TOOL_CALL -> stringResource(R.string.test_tool_call)
        }
        val state = when (kind) {
            ConnectionTestKind.NON_STREAMING -> nonStreaming
            ConnectionTestKind.STREAMING -> streaming
            ConnectionTestKind.TOOL_CALL -> toolCall
        }
        ConnectionTestDetailSheet(
            label = label,
            modelId = selectedModel,
            state = state,
            liveText = if (kind == ConnectionTestKind.STREAMING) streamingText else "",
            onDismiss = { detailKind = null },
        )
    }
}

@Composable
private fun GradioConnectionTestDialog(prov: ApiProvider, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember(prov.id) { mutableStateOf<TestState>(TestState.Idle) }

    fun inspect() {
        state = TestState.Loading
        scope.launch {
            state = runCatching {
                TestState.Ok(ConnectionTester.testNonStreaming(prov, prov.models.firstOrNull()?.id.orEmpty()))
            }.getOrElse { TestState.Err(it.message ?: it.toString()) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.test_connection)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space12)) {
                Text(
                    stringResource(R.string.gradio_endpoint_check_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TestResultRow(
                    label = stringResource(R.string.gradio_endpoint_label),
                    state = state,
                    onClick = {},
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { inspect() }, enabled = state !is TestState.Loading) {
                Text(if (state is TestState.Loading) stringResource(R.string.testing)
                    else stringResource(R.string.start_test))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun TestResultRow(
    label: String,
    state: TestState,
    liveText: String = "",
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = state !is TestState.Idle, onClick = onClick)
            .padding(vertical = Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space8),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
        when (state) {
            TestState.Idle -> Text("—", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
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
        if (state !is TestState.Idle) {
            Icon(
                Lucide.ChevronRight,
                contentDescription = stringResource(R.string.test_view_details),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionTestDetailSheet(
    label: String,
    modelId: String,
    state: TestState,
    liveText: String,
    onDismiss: () -> Unit,
) {
    val status = when (state) {
        TestState.Idle -> stringResource(R.string.test_status_waiting)
        TestState.Loading -> stringResource(R.string.testing)
        is TestState.Ok -> stringResource(R.string.test_status_success)
        is TestState.Err -> stringResource(R.string.test_status_failed)
    }
    val detail = when (state) {
        TestState.Idle -> ""
        TestState.Loading -> liveText
        is TestState.Ok -> liveText.ifBlank { state.text }
        is TestState.Err -> listOf(state.message, liveText)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.7f)
                .padding(horizontal = Space16)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space12),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.test_detail_title, label),
                    style = MaterialTheme.typography.titleMedium,
                )
                AppIconButton(
                    icon = Lucide.X,
                    contentDescription = stringResource(R.string.close),
                    onClick = onDismiss,
                )
            }
            DetailField(stringResource(R.string.test_detail_model), modelId)
            DetailField(stringResource(R.string.test_detail_status), status)
            Text(
                stringResource(R.string.test_detail_content),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
                Text(
                    detail.ifBlank { stringResource(R.string.test_detail_empty) },
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(Space12),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is TestState.Err) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(Space8))
        }
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space12),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun successGreen(): Color =
    if (isSystemInDarkTheme()) Color(0xFF86EFAC) else Color(0xFF166534)
