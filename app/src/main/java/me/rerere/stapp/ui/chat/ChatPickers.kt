package me.rerere.stapp.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Smile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.stapp.R
import me.rerere.stapp.data.api.ApiProvider
import me.rerere.stapp.data.character.CharacterRepository
import me.rerere.stapp.ui.components.ModelPickerList
import me.rerere.stapp.ui.components.PickerRow

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
internal fun ModelPickerSheet(
    providers: List<ApiProvider>,
    currentModel: String,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    PickerSheet(title = stringResource(R.string.select_model), onDismiss = onDismiss) {
        ModelPickerList(
            providers = providers,
            currentModel = currentModel,
            onSelect = onSelect,
        )
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
