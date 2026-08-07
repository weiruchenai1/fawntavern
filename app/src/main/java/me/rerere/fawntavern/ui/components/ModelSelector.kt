package me.rerere.fawntavern.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wallet
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ModelApi
import me.rerere.fawntavern.data.api.ModelInfo
import me.rerere.fawntavern.ui.api.ModelCapabilityTags
import me.rerere.fawntavern.ui.api.ProviderIcon

private const val BADGE_DEBOUNCE_MS = 100L

/** 模型选择器状态：面板可见性 + 当前模型 + 提供商列表。 */
@Stable
class ModelSelectorState internal constructor(
    currentModel: String,
    providers: List<ApiProvider>,
    filterEnabled: Boolean,
) {
    var currentModel by mutableStateOf(currentModel)
        private set
    var providers by mutableStateOf(providers)
        private set
    var filterEnabled by mutableStateOf(filterEnabled)
        private set
    var visible by mutableStateOf(false)
        private set

    /** 是否只展示已启用提供商；测试连接要能测未启用提供商，传 false */
    val shownProviders: List<ApiProvider>
        get() = if (filterEnabled) providers.filter { it.enabled } else providers

    fun open() { visible = true }
    fun close() { visible = false }

    internal fun update(currentModel: String, providers: List<ApiProvider>, filterEnabled: Boolean) {
        this.currentModel = currentModel
        this.providers = providers
        this.filterEnabled = filterEnabled
    }
}

@Composable
fun rememberModelSelectorState(
    currentModel: String,
    providers: List<ApiProvider>,
    filterEnabled: Boolean = true,
): ModelSelectorState {
    return remember { ModelSelectorState(currentModel, providers, filterEnabled) }
        .also { it.update(currentModel, providers, filterEnabled) }
}

/**
 * 模型选择器底部面板：
 * - State 类控制显隐，先以 Hidden 组装再展开，选择后走动画隐藏再销毁
 * - 行 UI 沿用「可用模型」面板样式：ProviderIcon + 模型名 + 能力标签，选中高亮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    state: ModelSelectorState,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit = {},
) {
    if (!state.visible) return

    val coroutineScope = rememberCoroutineScope()
    // 保留下滑关闭手势，只停打开/关闭两态
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    fun dismiss() {
        coroutineScope.launch {
            sheetState.hide()
            state.close()
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = {
            state.close()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            Modifier.padding(8.dp).fillMaxHeight(0.8f).imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ModelSelectorList(
                currentModel = state.currentModel,
                providers = state.shownProviders,
                onSelect = { providerId, modelId ->
                    onSelect(providerId, modelId)
                    dismiss()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, FlowPreview::class)
@Composable
private fun ColumnScope.ModelSelectorList(
    currentModel: String,
    providers: List<ApiProvider>,
    onSelect: (providerId: String, modelId: String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var searchKeywords by remember { mutableStateOf("") }

    val filteredModels = remember(providers, searchKeywords) {
        providers.associate { prov ->
            prov.id to prov.models.filter {
                val kw = searchKeywords.trim()
                kw.isBlank() || it.name.contains(kw, true) || it.id.contains(kw, true)
            }
        }
    }

    // 每个提供商分组在列表中的起始下标（stickyHeader 1 + 空态提示 1 + 模型数）
    val providerPositions = remember(providers, filteredModels) {
        var pos = 0
        providers.map { prov ->
            val start = pos
            pos += 1 // stickyHeader
            if (prov.models.isEmpty()) pos += 1 // 空态提示
            pos += filteredModels[prov.id].orEmpty().size
            prov.id to start
        }.toMap()
    }

    OutlinedTextField(
        value = searchKeywords,
        onValueChange = { searchKeywords = it },
        label = { Text(stringResource(R.string.filter_models)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    val lazyListState = rememberLazyListState()
    val badgeListState = rememberLazyListState()

    // 主列表滚动时，底部快捷条跟随滚到当前提供商对应的徽章
    LaunchedEffect(lazyListState, providerPositions) {
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(BADGE_DEBOUNCE_MS)
            .collect { index ->
                val current = providerPositions.entries.findLast { index > it.value }
                val target = providers.indexOfFirst { it.id == current?.key }
                if (target >= 0) badgeListState.animateScrollToItem(target)
                else badgeListState.requestScrollToItem(0)
            }
    }

    LazyColumn(
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(8.dp),
        modifier = Modifier.weight(1f).fillMaxWidth(),
    ) {
        if (providers.isEmpty()) {
            item {
                Text(stringResource(R.string.no_enabled_providers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp))
            }
        }

        providers.forEach { prov ->
            stickyHeader(key = "header:${prov.id}") {
                Row(
                    Modifier.padding(horizontal = 8.dp).padding(bottom = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(prov.name, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f))
                    ProviderBalanceText(prov)
                }
            }

            if (prov.models.isEmpty()) {
                item(key = "empty:${prov.id}") {
                    Text(stringResource(R.string.provider_no_models_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp))
                }
            }

            items(filteredModels[prov.id].orEmpty(), key = { "${prov.id}:${it.id}" }) { model ->
                ModelSelectorRow(
                    model = model,
                    selected = "${prov.id}::${model.id}" == currentModel,
                    onClick = { onSelect(prov.id, model.id) },
                )
            }
        }
    }

    // 底部提供商快捷跳转条
    if (providers.size > 1) {
        LazyRow(
            state = badgeListState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(providers, key = { it.id }) { provider ->
                AssistChip(
                    onClick = {
                        val position = providerPositions[provider.id] ?: 0
                        coroutineScope.launch { lazyListState.animateScrollToItem(position) }
                    },
                    label = { Text(provider.name) },
                    leadingIcon = { ProviderIcon(provider.name, size = 16.dp) },
                )
            }
        }
    }
}

/** 模型行：「可用模型」面板的行样式 + 单选高亮（选中 primaryContainer + Check）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelSelectorRow(
    model: ModelInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
             else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderIcon(model.id, size = 32.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(model.name, style = MaterialTheme.typography.titleSmall,
                color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ModelCapabilityTags(model)
            }
        }
    }
}

// 提供商余额（显示在提供商分组名右侧）
@Composable
internal fun ProviderBalanceText(prov: ApiProvider) {
    if (!prov.balanceEnabled || prov.balancePath.isBlank() || prov.apiKey.isBlank()) return
    var balance by remember(prov.id) { mutableStateOf("~") }
    LaunchedEffect(prov.id, prov.balancePath, prov.balanceJsonKey) {
        balance = try { ModelApi.getBalance(prov) } catch (_: Exception) { "--" }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Lucide.Wallet, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(balance, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
