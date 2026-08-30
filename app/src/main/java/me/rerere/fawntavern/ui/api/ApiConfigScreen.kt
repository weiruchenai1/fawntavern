package me.rerere.fawntavern.ui.api

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import kotlinx.coroutines.launch
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ModelType
import me.rerere.fawntavern.ui.components.AppTopBar
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.rememberReorderableList
import sh.calvin.reorderable.ReorderableItem

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
    val modelTypes = listOf(ModelType.CHAT, ModelType.IMAGE)
    val pagerState = rememberPagerState(pageCount = { modelTypes.size })
    val coroutineScope = rememberCoroutineScope()
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
                onDelete = { addingProvider = null },
            )
        }
        return
    }

    stateHolder.SaveableStateProvider("list") {
        BackHandler(onBack = onBack)

        val configuredCommunitySpace = config.providers.any { provider ->
            provider.type == "gradio" && provider.models.any { it.id == HF_Z_IMAGE_MODEL_ID }
        }
        val configuredOfficialSpace = config.providers.any { provider ->
            provider.type == "gradio" && provider.models.any { it.id == HF_Z_IMAGE_OFFICIAL_MODEL_ID }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    AppTopBar(stringResource(R.string.api_config), onBack)
                    PrimaryScrollableTabRow(
                        selectedTabIndex = pagerState.currentPage,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        edgePadding = 16.dp,
                    ) {
                        val tabs = listOf(
                            stringResource(R.string.chat_models_tab),
                            stringResource(R.string.image_models_tab),
                        )
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = index == pagerState.currentPage,
                                onClick = {
                                    coroutineScope.launch { pagerState.animateScrollToPage(index) }
                                },
                            ) {
                                Text(
                                    title,
                                    Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { addingProvider = ApiProvider() }) {
                    Icon(Lucide.Plus, stringResource(R.string.add_provider), Modifier.size(24.dp))
                }
            },
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().padding(padding),
                key = { modelTypes[it] },
                overscrollEffect = null,
            ) { page ->
                val selectedModelType = modelTypes[page]
                val visibleProviders = config.providers.filter { provider ->
                    if (selectedModelType == ModelType.IMAGE) provider.type == "gradio"
                    else provider.type != "gradio"
                }
                // Tab 只是同一份 provider 配置的任务视图；混合提供商仍共享地址和密钥。
                val (listState, reorderState) = rememberReorderableList(
                    items = visibleProviders,
                    keyOf = { it.id },
                ) { list ->
                    config = config.copy(providers = replaceVisibleProviderOrder(config.providers, list))
                    save()
                }

                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(Space12),
                ) {
                    if (selectedModelType == ModelType.IMAGE && !configuredOfficialSpace) {
                        item(key = "hf-z-image-official-template") {
                            HuggingFaceTemplateCard(
                                prov = officialHuggingFaceImageTemplate(),
                                onClick = { addingProvider = officialHuggingFaceImageTemplate() },
                            )
                        }
                    }
                    if (selectedModelType == ModelType.IMAGE && !configuredCommunitySpace) {
                        item(key = "hf-z-image-community-template") {
                            HuggingFaceTemplateCard(
                                prov = huggingFaceImageTemplate(),
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
                    itemsIndexed(visibleProviders, key = { _, provider -> provider.id }) { _, prov ->
                        ReorderableItem(reorderState, key = prov.id) { dragging ->
                            ProviderCard(
                                prov = prov,
                                modelCount = prov.models.size,
                                onClick = { editingId = prov.id },
                                dragging = dragging,
                                modifier = Modifier.longPressDraggableHandle(),
                            )
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}
