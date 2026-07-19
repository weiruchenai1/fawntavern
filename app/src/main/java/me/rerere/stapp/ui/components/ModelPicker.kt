package me.rerere.stapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wallet
import me.rerere.stapp.R
import me.rerere.stapp.data.api.ApiProvider
import me.rerere.stapp.data.api.ModelApi
import me.rerere.stapp.ui.api.ProviderIcon

/** 通用可选中行：图标 + 标签 + 尾部，选中时高亮。模型/角色选择等面板共用。 */
@Composable
internal fun PickerRow(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Box(Modifier.weight(1f)) { label() }
        trailing()
    }
}

/**
 * 模型选择列表：提供商分组 + 可勾选模型行。供底部面板（[me.rerere.stapp.ui.chat.ModelPickerSheet]）
 * 与其它容器（如 API 配置的测试连接弹窗）内联复用；调用方自行放进竖直（通常可滚动）容器里。
 * [currentModel] 为 "providerId::modelId"。
 *
 * @param filterEnabled 只显示已启用的提供商（聊天选模型用 true；测试连接要能测未启用的用 false）
 * @param showProviderHeader 是否显示提供商名称 + 余额分组头（单提供商测试时可关掉）
 */
@Composable
internal fun ModelPickerList(
    providers: List<ApiProvider>,
    currentModel: String,
    filterEnabled: Boolean = true,
    showProviderHeader: Boolean = true,
    onSelect: (providerId: String, modelId: String) -> Unit,
) {
    val shown = if (filterEnabled) providers.filter { it.enabled } else providers
    if (shown.isEmpty()) {
        Text(stringResource(R.string.no_enabled_providers),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp))
    }
    shown.forEach { prov ->
        if (showProviderHeader) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(prov.name, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f))
                ProviderBalanceText(prov)
            }
        }
        if (prov.models.isEmpty()) {
            Text(stringResource(R.string.provider_no_models_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
        prov.models.forEach { model ->
            val key = "${prov.id}::$model"
            val sel = key == currentModel
            PickerRow(
                selected = sel,
                onClick = { onSelect(prov.id, model) },
                icon = { ProviderIcon(model, size = 20.dp) },
                label = {
                    Text(model, style = MaterialTheme.typography.bodyMedium,
                        color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                               else MaterialTheme.colorScheme.onSurface)
                },
                trailing = {
                    if (sel) Icon(Lucide.Check, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                },
            )
        }
    }
}

// 提供商余额（显示在提供商名称行最右侧）
@Composable
private fun ProviderBalanceText(prov: ApiProvider) {
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
