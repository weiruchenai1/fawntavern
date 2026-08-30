package me.rerere.fawntavern.ui.api

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Package
import com.composables.icons.lucide.Settings
import kotlinx.coroutines.launch
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.Space8

@Composable
internal fun ProviderDetailScreen(
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
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

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
        val layoutDirection = LocalLayoutDirection.current
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(
                start = padding.calculateStartPadding(layoutDirection),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(layoutDirection),
                bottom = if (imeVisible) 0.dp else padding.calculateBottomPadding(),
            ),
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
