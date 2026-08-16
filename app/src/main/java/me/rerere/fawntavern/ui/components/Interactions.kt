package me.rerere.fawntavern.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager

/** Clears text-field focus when an otherwise unhandled page area is tapped. */
@Composable
fun Modifier.clearFocusOnTap(
    focusManager: FocusManager = LocalFocusManager.current,
): Modifier = pointerInput(focusManager) {
    detectTapGestures { focusManager.clearFocus() }
}

/**
 * 标准可点击：单击带 Material 波纹；传入 [onLongClick] 时额外支持长按，
 * 长按由 [combinedClickable] 自动触发系统 haptic（震动），[hapticFeedbackEnabled] 可关掉
 * 该反馈（偏好设置"长按触觉反馈"关闭时传 false）。
 *
 * 用于列表卡片、可点击行等主操作区域。
 */
fun Modifier.appClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    hapticFeedbackEnabled: Boolean = true,
): Modifier =
    if (onLongClick == null) {
        clickable(enabled = enabled, onClick = onClick)
    } else {
        combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
            hapticFeedbackEnabled = hapticFeedbackEnabled,
        )
    }

/**
 * 无波纹点击：用于聊天气泡内贴边的小图标（复制/重答/更多/版本切换箭头、思考折叠等）。
 *
 * 传 `interactionSource = null` 免去逐处 `remember { MutableInteractionSource() }` 的样板。
 */
fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = null,
    indication = null,
    enabled = enabled,
    onClick = onClick,
)

/** 拖拽排序抬起时的统一缩放值（越小抬起感越强）。 */
private const val DragLiftScale = 0.95f

/**
 * 拖拽排序的统一缩放反馈：抬起时轻微缩小并平滑过渡。
 */
@Composable
fun Modifier.draggableLiftScale(dragging: Boolean): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (dragging) DragLiftScale else 1f,
        label = "dragLiftScale",
    )
    return this.scale(scale)
}
