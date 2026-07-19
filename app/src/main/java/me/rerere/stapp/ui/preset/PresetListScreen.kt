package me.rerere.stapp.ui.preset

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.Lucide
import me.rerere.stapp.R
import me.rerere.stapp.data.preset.PresetRepository
import me.rerere.stapp.data.preset.StPreset
import me.rerere.stapp.ui.components.ImportableListScreen
import me.rerere.stapp.ui.components.Space16

@Composable
fun PresetListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedPreset by remember { mutableStateOf<StPreset?>(null) }

    if (selectedPreset != null) {
        PresetEditorScreen(preset = selectedPreset!!, onBack = { selectedPreset = null })
        return
    }

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
        listNames = { PresetRepository.listNames(context) },
        loadItem = { PresetRepository.load(context, it) },
        importItem = { PresetRepository.import(context, it).name },
        renameItem = { old, new -> PresetRepository.rename(context, old, new) },
        deleteItem = { PresetRepository.delete(context, it) },
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
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() },
                )
            }
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
