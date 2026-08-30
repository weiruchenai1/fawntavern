package me.rerere.fawntavern.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Smile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.settings.ImageGenerationStore
import me.rerere.fawntavern.ui.components.PickerRow
import me.rerere.fawntavern.ui.components.reasoningIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(
    title: String,
    onDismiss: () -> Unit,
    fillHeight: Boolean = true,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxHeight(0.8f) else Modifier)
                .padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Column(
                Modifier.fillMaxWidth()
                    .then(if (fillHeight) Modifier.weight(1f) else Modifier)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

/** 思考预算档位选择面板（聊天输入区的思考按钮唤起） */
@Composable
internal fun ReasoningPickerSheet(
    current: ReasoningLevel,
    onSelect: (ReasoningLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    PickerSheet(
        title = stringResource(R.string.thinking_budget),
        onDismiss = onDismiss,
        fillHeight = false,
    ) {
        Text(stringResource(R.string.thinking_budget_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp))
        ReasoningLevel.entries.forEach { level ->
            val sel = level == current
            val fg = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                     else MaterialTheme.colorScheme.onSurface
            PickerRow(
                selected = sel,
                onClick = { onSelect(level) },
                icon = { Icon(reasoningIcon(level), null, Modifier.size(24.dp), tint = fg) },
                label = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(level.label(), style = MaterialTheme.typography.bodyMedium, color = fg)
                        Text(level.description(), style = MaterialTheme.typography.labelSmall,
                            color = if (sel) fg else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                trailing = {
                    if (sel) Icon(Lucide.Check, null, Modifier.size(18.dp), tint = fg)
                },
            )
        }
    }
}

/** 图片生成控制面板，仅在当前模型声明图片输出能力时由输入区入口打开。 */
@Composable
internal fun ImageGenerationSettingsSheet(
    current: ImageGenerationSettings,
    useOpenAiSizes: Boolean,
    useGradioControls: Boolean,
    showOutputControls: Boolean = true,
    maxCount: Int = 5,
    onChange: (ImageGenerationSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(current.count, maxCount) {
        if (current.count > maxCount) onChange(current.copy(count = maxCount))
    }
    PickerSheet(
        title = stringResource(R.string.image_generation_settings),
        onDismiss = onDismiss,
        fillHeight = useGradioControls,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.image_generation_count_value, current.count),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Slider(
                    value = current.count.coerceAtMost(maxCount).toFloat(),
                    onValueChange = { onChange(current.copy(count = it.toInt())) },
                    valueRange = 1f..maxCount.coerceAtLeast(2).toFloat(),
                    steps = (maxCount - 2).coerceAtLeast(0),
                    enabled = maxCount > 1,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.image_generation_include_context),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.image_generation_include_context_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = current.includeContext,
                onCheckedChange = { onChange(current.copy(includeContext = it)) },
            )
        }
        if (useGradioControls) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.image_generation_steps_value, current.steps),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Slider(
                        value = current.steps.toFloat(),
                        onValueChange = { onChange(current.copy(steps = it.roundToInt())) },
                        valueRange = ImageGenerationStore.MIN_STEPS.toFloat()..ImageGenerationStore.MAX_STEPS.toFloat(),
                        steps = ImageGenerationStore.MAX_STEPS - ImageGenerationStore.MIN_STEPS - 1,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.image_generation_random_seed),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.image_generation_random_seed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = current.seed == null,
                    onCheckedChange = { random ->
                        onChange(current.copy(seed = if (random) null else current.seed ?: 42))
                    },
                )
            }

            if (current.seed != null) {
                var seedText by remember(current.seed) { mutableStateOf(current.seed.toString()) }
                val parsedSeed = seedText.toIntOrNull()?.takeIf { it >= 0 }
                OutlinedTextField(
                    value = seedText,
                    onValueChange = { value ->
                        seedText = value.filter(Char::isDigit).take(10)
                        seedText.toIntOrNull()?.takeIf { it >= 0 }?.let { seed ->
                            onChange(current.copy(seed = seed))
                        }
                    },
                    label = { Text(stringResource(R.string.image_generation_seed)) },
                    singleLine = true,
                    isError = parsedSeed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!showOutputControls) {
            Text(
                stringResource(R.string.image_generation_output_auto),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else if (useOpenAiSizes) {
            ImageGenerationOptionFlow(
                label = stringResource(R.string.image_generation_size),
                value = openAiDisplaySize(current.aspectRatio),
                options = OPENAI_IMAGE_SIZES,
                onSelect = { size ->
                    onChange(current.copy(aspectRatio = openAiAspectRatio(size)))
                },
            )
            ImageGenerationOptionFlow(
                label = stringResource(R.string.image_generation_quality),
                value = current.quality,
                options = ImageGenerationStore.QUALITIES,
                onSelect = { onChange(current.copy(quality = it)) },
            )
        } else {
            ImageGenerationOptionFlow(
                label = stringResource(R.string.image_generation_aspect_ratio),
                value = current.aspectRatio,
                options = ImageGenerationStore.ASPECT_RATIOS,
                onSelect = { onChange(current.copy(aspectRatio = it)) },
            )
            ImageGenerationOptionFlow(
                label = stringResource(R.string.image_generation_resolution),
                value = current.resolution,
                options = ImageGenerationStore.RESOLUTIONS,
                onSelect = { onChange(current.copy(resolution = it)) },
            )
        }
    }
}

private val OPENAI_IMAGE_SIZES = listOf("auto", "1024x1024", "1536x1024", "1024x1536")

private fun openAiDisplaySize(aspectRatio: String): String {
    if (aspectRatio.equals("auto", ignoreCase = true)) return "auto"
    val parts = aspectRatio.split(':', limit = 2)
    val width = parts.getOrNull(0)?.toDoubleOrNull() ?: return "auto"
    val height = parts.getOrNull(1)?.toDoubleOrNull() ?: return "auto"
    return when {
        width <= 0.0 || height <= 0.0 -> "auto"
        width == height -> "1024x1024"
        width > height -> "1536x1024"
        else -> "1024x1536"
    }
}

private fun openAiAspectRatio(size: String): String = when (size) {
    "1024x1024" -> "1:1"
    "1536x1024" -> "3:2"
    "1024x1536" -> "2:3"
    else -> "auto"
}

@Composable
private fun ImageGenerationOptionFlow(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            FlowRow(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    val selected = option == value
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { onSelect(option) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            imageGenerationOptionLabel(option),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun imageGenerationOptionLabel(option: String): String = when (option) {
    "auto" -> stringResource(R.string.image_generation_auto)
    "low" -> stringResource(R.string.image_generation_quality_low)
    "medium" -> stringResource(R.string.image_generation_quality_medium)
    "high" -> stringResource(R.string.image_generation_quality_high)
    else -> option
}

@Composable
internal fun ReasoningLevel.label(): String = stringResource(
    when (this) {
        ReasoningLevel.OFF -> R.string.reasoning_off
        ReasoningLevel.AUTO -> R.string.reasoning_auto
        ReasoningLevel.LOW -> R.string.reasoning_low
        ReasoningLevel.MEDIUM -> R.string.reasoning_medium
        ReasoningLevel.HIGH -> R.string.reasoning_high
        ReasoningLevel.XHIGH -> R.string.reasoning_xhigh
    }
)

/** 档位说明：有 token 预算的档位直接标出预算，便于对照各家模型的上限 */
@Composable
private fun ReasoningLevel.description(): String = when (this) {
    ReasoningLevel.OFF -> stringResource(R.string.reasoning_off_desc)
    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto_desc)
    else -> androidx.compose.ui.res.pluralStringResource(R.plurals.reasoning_budget_fmt, budgetTokens, budgetTokens)
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
