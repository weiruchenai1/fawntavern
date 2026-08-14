package me.rerere.fawntavern.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Trash2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.search.SearchCommonOptions
import me.rerere.fawntavern.data.search.SearchServiceOptions
import me.rerere.fawntavern.data.search.createSearchService
import me.rerere.fawntavern.ui.api.ProviderIcon
import me.rerere.fawntavern.ui.components.AppTopBar
import me.rerere.fawntavern.ui.components.PickerRow
import me.rerere.fawntavern.ui.components.SettingsSubPage
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.components.appClickable
import me.rerere.fawntavern.ui.components.draggableLiftScale
import me.rerere.fawntavern.ui.components.rememberReorderableList
import sh.calvin.reorderable.ReorderableItem

/**
 * 搜索服务设置页：已配置提供商的可排序卡片列表 + 添加（右上角） + 通用选项（结果条数）。
 * 卡片长按弹出下拉菜单（编辑/删除），点卡片进编辑页；编辑页内含该提供商的参数与测试搜索。
 */
@Composable
fun WebSearchConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { WebSearchConfigController(AndroidWebSearchConfigDataSource(context)) }
    var config by remember(controller) { mutableStateOf(controller.load()) }
    val services = config.services
    val configWasRecovered = config.recovered
    val configRecoveredMessage = stringResource(R.string.search_config_recovered)
    LaunchedEffect(configWasRecovered) {
        if (configWasRecovered) Toast.makeText(
            context, configRecoveredMessage, Toast.LENGTH_LONG
        ).show()
    }
    var editingId by remember { mutableStateOf<String?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    val stateHolder = rememberSaveableStateHolder()

    if (editingId != null) {
        stateHolder.SaveableStateProvider("detail") {
            val service = services.find { it.id == editingId } ?: return@SaveableStateProvider
            SearchProviderEditScreen(
                service = service,
                resultSize = config.resultSize,
                onBack = { editingId = null },
                onSave = { updated ->
                    config = controller.replace(config, services.map { if (it.id == updated.id) updated else it })
                },
            )
        }
        return
    }

    if (showAddSheet) {
        AddSearchProviderSheet(
            onDismiss = { showAddSheet = false },
            onAdd = { options ->
                config = controller.add(config, options)
                showAddSheet = false
            },
        )
    }

    stateHolder.SaveableStateProvider("list") {
        BackHandler(onBack = onBack)

        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.search_service),
                    onBack = onBack,
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showAddSheet = true }) {
                    Icon(Lucide.Plus, stringResource(R.string.search_add_provider), Modifier.size(24.dp))
                }
            },
        ) { padding ->
            val (listState, reorderState) = rememberReorderableList(
                items = services,
                keyOf = { it.id },
            ) { list ->
                config = controller.replace(config, list)
            }

            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                state = listState,
                contentPadding = PaddingValues(Space16),
                verticalArrangement = Arrangement.spacedBy(Space12),
            ) {
                item("title") {
                    Text(stringResource(R.string.search_provider), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                itemsIndexed(services, key = { _, s -> s.id }) { _, service ->
                    ReorderableItem(reorderState, key = service.id) { dragging ->
                        SearchProviderCard(
                            service = service,
                            dragging = dragging,
                            canDelete = services.size > 1,
                            onEdit = { editingId = service.id },
                            onDelete = {
                                config = controller.remove(config, service.id)
                            },
                            modifier = Modifier.longPressDraggableHandle(),
                        )
                    }
                }
                item("common_title") {
                    Text(stringResource(R.string.search_common_options), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                item("common") {
                    CommonOptionsCard(
                        resultSize = config.resultSize,
                        onResultSizeChange = { config = controller.setResultSize(config, it) },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

/** 搜索提供商卡片：图标 + 名称 + 右侧拖拽手柄；点卡片编辑，长按弹下拉菜单 */
@Composable
private fun SearchProviderCard(
    service: SearchServiceOptions,
    dragging: Boolean,
    canDelete: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .draggableLiftScale(dragging)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(end = Space16),
            horizontalArrangement = Arrangement.spacedBy(Space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f)
                    .appClickable(onClick = onEdit, onLongClick = { showMenu = true })
                    .padding(start = Space16, top = Space16, bottom = Space16),
                horizontalArrangement = Arrangement.spacedBy(Space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderIcon(service.displayName, size = 32.dp)
                Text(service.displayName, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Lucide.GripVertical, stringResource(R.string.reorder),
                Modifier.size(24.dp).then(modifier),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete),
                    color = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                leadingIcon = { Icon(Lucide.Trash2, null, Modifier.size(18.dp),
                    tint = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                enabled = canDelete,
                onClick = { showMenu = false; onDelete() },
            )
        }
    }
}

/** 通用选项：结果条数等全局参数（所有提供商共用） */
@Composable
private fun CommonOptionsCard(
    resultSize: Int,
    onResultSizeChange: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(Space16),
        verticalArrangement = Arrangement.spacedBy(Space12),
    ) {
        Text(stringResource(R.string.search_result_count, resultSize),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Slider(
            value = resultSize.toFloat(),
            onValueChange = { onResultSizeChange(it.toInt()) },
            valueRange = 3f..10f,
            steps = 6,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
    }
}

/** 添加搜索提供商底部面板：预设提供商单选，确认后加入列表 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSearchProviderSheet(
    onDismiss: () -> Unit,
    onAdd: (SearchServiceOptions) -> Unit,
) {
    var selectedKey by remember { mutableStateOf(SearchServiceOptions.ALL.first().key) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space16).padding(bottom = Space16),
            verticalArrangement = Arrangement.spacedBy(Space12),
        ) {
            Text(stringResource(R.string.search_add_provider), style = MaterialTheme.typography.titleMedium)
            // 预设过多时列表区独立滚动，标题与添加按钮固定
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space12),
            ) {
                SearchServiceOptions.ALL.forEach { preset ->
                    PickerRow(
                        selected = preset.key == selectedKey,
                        onClick = { selectedKey = preset.key },
                        icon = { ProviderIcon(preset.displayName, size = 28.dp) },
                        label = { Text(preset.displayName, style = MaterialTheme.typography.bodyMedium) },
                        trailing = {
                            if (preset.key == selectedKey) {
                                Icon(Lucide.Check, null, Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        },
                    )
                }
            }
            Button(onClick = { onAdd(SearchServiceOptions.fromKey(selectedKey)) },
                modifier = Modifier.fillMaxWidth()) {
                Icon(Lucide.Plus, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Space8))
                Text(stringResource(R.string.add))
            }
        }
    }
}

/** 搜索提供商编辑页：参数配置（变更即落盘）+ 测试搜索 */
@Composable
private fun SearchProviderEditScreen(
    service: SearchServiceOptions,
    resultSize: Int,
    onBack: () -> Unit,
    onSave: (SearchServiceOptions) -> Unit,
) {
    var draft by remember(service.id) { mutableStateOf(service) }
    BackHandler(onBack = onBack)

    SettingsSubPage(draft.displayName, onBack) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer).padding(Space16),
            verticalArrangement = Arrangement.spacedBy(Space12),
        ) {
            Text(stringResource(R.string.search_provider_config), style = MaterialTheme.typography.titleMedium)
            ProviderOptionsEditor(draft) { updated ->
                draft = updated
                onSave(updated)
            }
        }
        SearchTesterCard(draft, resultSize)
    }
}

/** 各提供商的配置字段 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProviderOptionsEditor(options: SearchServiceOptions, onUpdate: (SearchServiceOptions) -> Unit) {
    when (options) {
        is SearchServiceOptions.TavilyOptions -> {
            ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
                onUpdate(options.copy(apiKey = it))
            }
            DepthSegmented(options.depth) { onUpdate(options.copy(depth = it)) }
        }
        is SearchServiceOptions.ExaOptions -> ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
            onUpdate(options.copy(apiKey = it))
        }
        is SearchServiceOptions.ZhipuOptions -> ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
            onUpdate(options.copy(apiKey = it))
        }
        is SearchServiceOptions.BraveOptions -> ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
            onUpdate(options.copy(apiKey = it))
        }
        is SearchServiceOptions.SearXNGOptions -> {
            ConfigField(stringResource(R.string.searxng_url), options.url) { onUpdate(options.copy(url = it)) }
            ConfigField(stringResource(R.string.searxng_engines), options.engines) { onUpdate(options.copy(engines = it)) }
            ConfigField(stringResource(R.string.searxng_language), options.language) { onUpdate(options.copy(language = it)) }
            ConfigField(stringResource(R.string.searxng_username), options.username) { onUpdate(options.copy(username = it)) }
            ConfigField(stringResource(R.string.searxng_password), options.password, secret = true) {
                onUpdate(options.copy(password = it))
            }
        }
        is SearchServiceOptions.DuckDuckGoOptions -> ConfigField(stringResource(R.string.duckduckgo_region), options.region) {
            onUpdate(options.copy(region = it))
        }
        is SearchServiceOptions.LinkUpOptions -> ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
            onUpdate(options.copy(apiKey = it))
        }
        is SearchServiceOptions.MetasoOptions -> ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
            onUpdate(options.copy(apiKey = it))
        }
        is SearchServiceOptions.OllamaOptions -> ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
            onUpdate(options.copy(apiKey = it))
        }
        is SearchServiceOptions.JinaOptions -> ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
            onUpdate(options.copy(apiKey = it))
        }
        is SearchServiceOptions.BochaOptions -> ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
            onUpdate(options.copy(apiKey = it))
        }
        is SearchServiceOptions.PerplexityOptions -> ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
            onUpdate(options.copy(apiKey = it))
        }
        is SearchServiceOptions.SerperOptions -> {
            ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
                onUpdate(options.copy(apiKey = it))
            }
            ConfigField(stringResource(R.string.serper_gl), options.gl) { onUpdate(options.copy(gl = it)) }
            ConfigField(stringResource(R.string.serper_hl), options.hl) { onUpdate(options.copy(hl = it)) }
            ConfigField(stringResource(R.string.serper_tbs), options.tbs) { onUpdate(options.copy(tbs = it)) }
            ConfigField(stringResource(R.string.serper_page), if (options.page > 1) options.page.toString() else "") {
                onUpdate(options.copy(page = it.toIntOrNull() ?: 1))
            }
        }
        is SearchServiceOptions.QueritOptions -> {
            ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
                onUpdate(options.copy(apiKey = it))
            }
            ConfigField(stringResource(R.string.querit_sites_include), options.sitesInclude) {
                onUpdate(options.copy(sitesInclude = it))
            }
            ConfigField(stringResource(R.string.querit_sites_exclude), options.sitesExclude) {
                onUpdate(options.copy(sitesExclude = it))
            }
            ConfigField(stringResource(R.string.querit_time_range), options.timeRange) {
                onUpdate(options.copy(timeRange = it))
            }
            ConfigField(stringResource(R.string.querit_countries), options.countries) {
                onUpdate(options.copy(countries = it))
            }
            ConfigField(stringResource(R.string.querit_languages), options.languages) {
                onUpdate(options.copy(languages = it))
            }
        }
        is SearchServiceOptions.GrokOptions -> {
            ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
                onUpdate(options.copy(apiKey = it))
            }
            ConfigField(stringResource(R.string.grok_model), options.model) { onUpdate(options.copy(model = it)) }
            ConfigField(stringResource(R.string.grok_reasoning_effort), options.reasoningEffort) {
                onUpdate(options.copy(reasoningEffort = it))
            }
            ConfigField(stringResource(R.string.grok_custom_url), options.customUrl) {
                onUpdate(options.copy(customUrl = it))
            }
            ConfigField(stringResource(R.string.grok_system_prompt), options.systemPrompt) {
                onUpdate(options.copy(systemPrompt = it))
            }
        }
        is SearchServiceOptions.BingLocalOptions -> Text(stringResource(R.string.search_no_config),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tavily 搜索深度：basic / advanced 单选 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DepthSegmented(depth: String, onChange: (String) -> Unit) {
    val options = listOf("basic", "advanced")
    Column(verticalArrangement = Arrangement.spacedBy(Space8)) {
        Text(stringResource(R.string.search_depth), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, d ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    selected = depth == d,
                    onClick = { onChange(d) },
                ) {
                    Text(d.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

/** 测试搜索：输入关键词实时跑一遍该提供商，展示前几条结果 */
@Composable
private fun SearchTesterCard(options: SearchServiceOptions, resultSize: Int) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var query by remember(options.id) { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(Space16),
        verticalArrangement = Arrangement.spacedBy(Space12),
    ) {
        Text(stringResource(R.string.search_test), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_test_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                if (query.isBlank() || running) return@Button
                running = true
                output = null
                scope.launch {
                    val text = withContext(Dispatchers.IO) {
                        runCatching {
                            val result = createSearchService(options)
                                .search(query, SearchCommonOptions(resultSize), options)
                                .getOrThrow()
                            if (result.items.isEmpty()) resources.getString(R.string.web_search_no_results)
                            else result.items.take(3).joinToString("\n\n") {
                                "${it.title}\n${it.url}\n${it.text}"
                            }
                        }.getOrElse { resources.getString(R.string.web_search_error_fmt, it.message ?: "") }
                    }
                    output = text
                    running = false
                }
            },
            enabled = query.isNotBlank() && !running,
        ) {
            Icon(Lucide.Search, null, Modifier.size(16.dp))
            Spacer(Modifier.padding(4.dp))
            Text(if (running) stringResource(R.string.searching) else stringResource(R.string.search_run))
        }
        output?.let { txt ->
            Text(txt, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(Space8))
        }
    }
}

/** 配置表单字段：本地暂存 + 变更即落盘；secret 字段默认掩码、尾部眼睛按钮切换可见 */
@Composable
private fun ConfigField(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    // 以 value 为 key：外部值变化（如切换提供商类型）时本地暂存同步重置
    var text by remember(value) { mutableStateOf(value) }
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation()
                               else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password)
                          else KeyboardOptions.Default,
        trailingIcon = if (!secret) null else {
            {
                androidx.compose.material3.IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Lucide.EyeOff else Lucide.Eye, null, Modifier.size(20.dp))
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
