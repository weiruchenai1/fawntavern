package me.rerere.fawntavern.ui.preset

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.Lucide
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.preset.StPreset
import me.rerere.fawntavern.ui.components.ImportableListScreen
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.components.appClickable

@Composable
fun PresetListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { PresetDataController(context) }
    var selectedPreset by remember { mutableStateOf<StPreset?>(null) }
    // SaveableStateHolder：进入编辑器时列表离开组合，其 LazyListState 被暂存；
    // 返回时恢复，避免列表滚动位置丢失（跳回顶部）。
    val stateHolder = rememberSaveableStateHolder()

    if (selectedPreset != null) {
        stateHolder.SaveableStateProvider("editor") {
            PresetEditorScreen(preset = selectedPreset!!, onBack = { selectedPreset = null })
        }
        return
    }

    stateHolder.SaveableStateProvider("list") {
        BackHandler(onBack = onBack)

        ImportableListScreen(
        titleRes = R.string.presets,
        onBack = onBack,
        importMimeType = "application/json",
        emptyIcon = Lucide.FileJson,
        emptyTitleRes = R.string.no_presets_title,
        emptyDescRes = R.string.no_presets_desc,
        renameLabelRes = R.string.toast_rename_preset_label,
        deleteTitleRes = R.string.delete_preset_title,
        deleteMsgFmtRes = R.string.delete_preset_msg_fmt,
        listNames = controller::names,
        loadItem = controller::load,
        importItem = controller::import,
        renameItem = controller::rename,
        deleteItem = controller::delete,
        onOpen = { selectedPreset = it },
        itemCard = { name, p, onClick, onLongPress ->
            PresetCard(
                name = name,
                model = p.openaiModel.ifBlank { p.claudeModel.ifBlank { p.googleModel } },
                promptCount = p.prompts.size,
                onClick = onClick,
                onLongPress = onLongPress,
            )
        },
    )
    } // SaveableStateProvider("list")
}

@Composable
private fun PresetCard(
    name: String,
    model: String,
    promptCount: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .appClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(Space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = buildString {
                    if (model.isNotBlank()) append(model)
                    if (promptCount > 0) {
                        if (model.isNotBlank()) append(" · ")
                        append(stringResource(R.string.prompts_count_fmt, promptCount))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Lucide.ChevronRight, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
