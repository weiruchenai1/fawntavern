package me.rerere.fawntavern.ui.settings

import android.graphics.BitmapFactory
import me.rerere.fawntavern.data.settings.FontSizeStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiConfigStore
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.settings.UserProfileStore
import me.rerere.fawntavern.ui.chat.AIMsg
import me.rerere.fawntavern.ui.chat.UserMsg
import me.rerere.fawntavern.ui.components.AppTopBar

private data class ScalePreset(val labelResId: Int, val scale: Float)

private val PRESETS = listOf(
    ScalePreset(R.string.font_size_small, 0.85f),
    ScalePreset(R.string.font_size_default, 1.0f),
    ScalePreset(R.string.font_size_large, 1.15f),
    ScalePreset(R.string.font_size_xlarge, 1.3f),
)

private const val SCALE_MIN = 0.8f
private const val SCALE_MAX = 1.4f

/** 若与最近的预设档位差值在容差内，则吸附到该预设。 */
private fun snapToPreset(value: Float): Float {
    val closest = PRESETS.minByOrNull { kotlin.math.abs(it.scale - value) } ?: return value
    return if (kotlin.math.abs(closest.scale - value) < 0.03f) closest.scale else value
}

@Composable
fun FontSizeScreen(onBack: () -> Unit, currentScale: Float = 1.0f) {
    val context = LocalContext.current
    var selectedScale by remember { mutableFloatStateOf(currentScale) }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(stringResource(R.string.font_size), onBack) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
        ) {
            // 预览直接复用聊天页的 UserMsg / AIMsg，喂真实的用户名/头像/当前模型，
            // 保证与实际渲染（含展开思考、工具栏交互）永远一致，不再维护一份假组件
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.preview_effect), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)

                val userName = remember { UserProfileStore.getName(context) }
                val avatar = remember {
                    UserProfileStore.getAvatarPath(context)?.let { path ->
                        try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
                    }
                }
                val modelId = remember { ApiConfigStore.loadConfig(context).currentModel.substringAfter("::", "") }
                    .ifBlank { stringResource(R.string.no_model_selected) }

                UserMsg(
                    name = userName,
                    text = stringResource(R.string.preview_user_text),
                    onCopy = {}, onRegenerate = {}, onMore = {},
                    scale = selectedScale,
                    avatarBitmap = avatar,
                )

                AIMsg(
                    msg = ChatMessage(
                        role = "assistant",
                        content = stringResource(R.string.preview_ai_text),
                        reasoning = stringResource(R.string.preview_ai_reasoning),
                        model = modelId,
                        reasoningMs = 2800,
                    ),
                    isStreaming = false,
                    onCopy = {}, onRegenerate = {}, onMore = {},
                    scale = selectedScale,
                )

                Spacer(Modifier.height(16.dp))
            }

            // ── 底部滑杆区域 ──
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 16.dp),
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    PRESETS.forEach { preset ->
                        val isSel = kotlin.math.abs(selectedScale - preset.scale) < 0.03f
                        Text(
                            stringResource(preset.labelResId),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSel) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Slider(
                    value = selectedScale,
                    onValueChange = { raw ->
                        selectedScale = snapToPreset(raw)
                        FontSizeStore.setScale(context, selectedScale)
                    },
                    valueRange = SCALE_MIN..SCALE_MAX,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )

                Text(
                    "${"%.0f".format(selectedScale * 100)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
