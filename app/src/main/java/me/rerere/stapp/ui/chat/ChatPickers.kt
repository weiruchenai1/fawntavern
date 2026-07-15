package me.rerere.stapp.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Smile
import com.composables.icons.lucide.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.stapp.R
import me.rerere.stapp.data.api.ApiProvider
import me.rerere.stapp.data.api.ModelApi
import me.rerere.stapp.data.character.CharacterRepository
import me.rerere.stapp.ui.api.ProviderIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // 固定 80% 屏高并跳过半开状态，避免"半开→上划全屏"造成误触
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.8f)
                .padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun PickerRow(
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

@Composable
internal fun ModelPickerSheet(
    providers: List<ApiProvider>,
    currentModel: String,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    PickerSheet(title = stringResource(R.string.select_model), onDismiss = onDismiss) {
        val enabledProviders = providers.filter { it.enabled }
        if (enabledProviders.isEmpty()) {
            Text(stringResource(R.string.no_enabled_providers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
        enabledProviders.forEach { prov ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(prov.name, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f))
                ProviderBalanceText(prov)
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
                    icon = {
                        ProviderIcon(model, size = 20.dp)
                    },
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
}

// 提供商余额（显示在模型面板提供商名称行的最右侧）
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

@Composable
internal fun CharacterPickerSheet(
    currentFileName: String,
    onSelect: (fileName: String, displayName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var charNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var thumbs by remember { mutableStateOf<Map<String, android.graphics.Bitmap>>(emptyMap()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val repo = CharacterRepository
            val names = repo.listNames(context)
            charNames = names.associateWith { fileName ->
                try { repo.load(context, fileName).name } catch (_: Exception) { fileName }
            }
            thumbs = names.mapNotNull { n ->
                repo.decodeImageThumb(context, n)?.let { n to it }
            }.toMap()
        }
    }
    PickerSheet(title = stringResource(R.string.select_character), onDismiss = onDismiss) {
        // "默认角色"是首启播种的真实空白卡文件，随其他角色卡一起出现在 charNames 里
        if (charNames.isEmpty()) {
            Text(stringResource(R.string.no_characters), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            charNames.forEach { (fileName, displayName) ->
                val sel = fileName == currentFileName
                PickerRow(
                    selected = sel,
                    onClick = { onSelect(fileName, displayName) },
                    icon = {
                        val thumb = thumbs[fileName]
                        if (thumb != null) {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(Lucide.Smile, null, Modifier.size(32.dp),
                                tint = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                                       else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    label = {
                        Text(displayName, style = MaterialTheme.typography.bodyMedium,
                            color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                                   else MaterialTheme.colorScheme.onSurface)
                    },
                )
            }
        }
    }
}
