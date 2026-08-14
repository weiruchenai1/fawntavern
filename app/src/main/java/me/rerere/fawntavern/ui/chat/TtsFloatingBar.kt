package me.rerere.fawntavern.ui.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FastForward
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.X
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.speech.TtsUiState

/** 展开的控制区固定宽度（用固定目标宽度插值，不测量自然宽度） */
private val ControlsWidth = 112.dp

/**
 * TTS 朗读悬浮工具栏：朗读时悬浮于屏幕上的圆形悬浮窗。
 * 播放/暂停按钮内叠两层圆形进度环（外圈=当前段位置、内圈=整段朗读进度），
 * 右侧停止、展开后出现语速切换与快进 5 秒。
 */
@Composable
internal fun TtsFloatingBar(
    state: TtsUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onFastForward: () -> Unit,
    onCycleSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 不在这里早退：保持内容恒非空，避免悬浮窗隐藏后尺寸坍缩、再次显示时是 0 尺寸
    var expand by remember { mutableStateOf(false) }
    // 宽度动画驱动展开（同 kelivo）：内容始终在组合里，按插值宽度裁切 + 淡入淡出
    val controlsSlotWidth by animateDpAsState(
        targetValue = if (expand) ControlsWidth else 0.dp,
        animationSpec = tween(220),
        label = "ttsControlsWidth",
    )
    val expansion = (controlsSlotWidth / ControlsWidth).coerceIn(0f, 1f)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = { if (state.paused) onResume() else onPause() },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (state.paused) Lucide.Play else Lucide.Pause,
                        stringResource(if (state.paused) R.string.tts_resume else R.string.tts_pause),
                        Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    // 双层圆形进度环：外圈当前段位置，内圈整段朗读进度
                    CircularProgressIndicator(
                        progress = {
                            if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
                        },
                        color = MaterialTheme.colorScheme.tertiary,
                        strokeWidth = 2.dp,
                        trackColor = Color.Transparent,
                    )
                    CircularProgressIndicator(
                        progress = {
                            if (state.totalChunks > 0) (state.chunkIndex + 1).toFloat() / state.totalChunks else 0f
                        },
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(2.dp),
                        strokeWidth = 2.dp,
                        trackColor = Color.Transparent,
                    )
                }
            }

            IconButton(onClick = onStop) {
                Icon(Lucide.X, stringResource(R.string.stop_speaking),
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 控制插槽：始终在组合，requiredWidth 按动画宽度裁切、alpha 按展开进度淡入；
            // 内容用 requiredWidth 固定展开宽度排布，不随插槽宽度挤压
            Box(Modifier.requiredWidth(controlsSlotWidth).clipToBounds()) {
                Row(
                    Modifier.requiredWidth(ControlsWidth).alpha(expansion),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCycleSpeed) {
                        Text(stringResource(R.string.tts_speed_fmt, state.speed))
                    }
                    IconButton(onClick = onFastForward) {
                        Icon(Lucide.FastForward, stringResource(R.string.tts_fast_forward),
                            Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            IconButton(onClick = { expand = !expand }) {
                Icon(if (expand) Lucide.ChevronLeft else Lucide.ChevronRight, null,
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
