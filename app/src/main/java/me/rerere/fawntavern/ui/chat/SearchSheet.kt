package me.rerere.fawntavern.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Bolt
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.Lucide
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.search.SearchServiceOptions
import me.rerere.fawntavern.ui.api.ProviderIcon

/**
 * 联网搜索面板：顶部为 globe 图标 + 网络搜索标题 + 设置入口（bolt）+ 总开关，
 * 下方为可选搜索服务商卡片。开关开启后发送消息时用最近一条用户消息联网搜索并注入结果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchPickerSheet(
    searchEnabled: Boolean,
    builtInSearchAvailable: Boolean,
    builtInSearchEnabled: Boolean,
    services: List<SearchServiceOptions>,
    selectedIndex: Int,
    onToggleSearch: () -> Unit,
    onToggleBuiltInSearch: () -> Unit,
    onSelectProvider: (Int) -> Unit,
    onOpenConfig: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (builtInSearchAvailable) {
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Lucide.Earth, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.model_builtin_search),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Switch(
                            checked = builtInSearchEnabled,
                            onCheckedChange = { onToggleBuiltInSearch() },
                        )
                    }
                }
            }

            // 模型内置搜索开启后，不再呈现 App 网络搜索的开关和服务选择，避免两种搜索同时下发。
            if (!builtInSearchEnabled) {
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Lucide.Earth, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                stringResource(R.string.web_search),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                stringResource(if (searchEnabled) R.string.web_search_enabled else R.string.web_search_disabled),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onOpenConfig) {
                            Icon(
                                Lucide.Bolt,
                                stringResource(R.string.search_open_config),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = searchEnabled,
                            onCheckedChange = { onToggleSearch() },
                        )
                    }
                }

                // 可选搜索服务商卡片：图标 + 名称，点击选中
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed(services, key = { _, s -> s.id }) { index, opt ->
                        val sel = index == selectedIndex
                        Card(
                            onClick = { onSelectProvider(index) },
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(
                                containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                                contentColor = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 无具体图标，用首字图标（ProviderIcon 的回退样式）
                                ProviderIcon(opt.displayName, size = 28.dp)
                                Text(opt.displayName, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
