package me.rerere.fawntavern.ui.api

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Lucide
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ModelInfo
import me.rerere.fawntavern.data.api.ModelType
import me.rerere.fawntavern.data.api.Modality
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.components.draggableLiftScale

internal const val HF_Z_IMAGE_URL = "https://mrfakename-z-image-turbo.hf.space"
internal const val HF_Z_IMAGE_MODEL_ID = "z-image-turbo"

internal fun huggingFaceImageTemplate() = ApiProvider(
    name = "Hugging Face Space",
    type = "gradio",
    baseUrl = HF_Z_IMAGE_URL,
    apiPath = "/generate_image",
    models = listOf(
        ModelInfo(
            id = HF_Z_IMAGE_MODEL_ID,
            displayName = "Z-Image Turbo",
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.IMAGE),
            type = ModelType.IMAGE,
        ),
    ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderCard(
    prov: ApiProvider,
    modelCount: Int = prov.models.size,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth()
            .draggableLiftScale(dragging)
            .clip(RoundedCornerShape(12.dp))
            .background(if (prov.enabled) MaterialTheme.colorScheme.surfaceContainer
                        else MaterialTheme.colorScheme.errorContainer)
            .clickable { onClick() }
            .padding(horizontal = Space16, vertical = Space12),
        horizontalArrangement = Arrangement.spacedBy(Space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderIcon(prov.name, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
            Text(prov.name, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (prov.baseUrl.isNotBlank()) {
                Text(prov.baseUrl, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space4),
                verticalArrangement = Arrangement.spacedBy(Space4)) {
                Tag(type = if (prov.enabled) TagType.SUCCESS else TagType.WARNING) {
                    Text(if (prov.enabled) stringResource(R.string.enabled_label)
                         else stringResource(R.string.disabled_label))
                }
                Tag(type = TagType.INFO) {
                    Text(androidx.compose.ui.res.pluralStringResource(R.plurals.models_count_fmt, modelCount, modelCount))
                }
            }
        }
        // 拖动手柄：长按后上下拖拽排序
        Icon(
            Lucide.GripVertical, stringResource(R.string.reorder),
            Modifier.size(24.dp).then(modifier),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun HuggingFaceTemplateCard(onClick: () -> Unit) {
    ProviderCard(
        prov = huggingFaceImageTemplate(),
        onClick = onClick,
    )
}

internal fun replaceVisibleProviderOrder(
    all: List<ApiProvider>,
    reordered: List<ApiProvider>,
): List<ApiProvider> {
    val ids = reordered.mapTo(hashSetOf()) { it.id }
    val iterator = reordered.iterator()
    return all.map { provider -> if (provider.id in ids) iterator.next() else provider }
}
