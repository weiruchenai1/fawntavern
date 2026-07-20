package me.rerere.stapp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale

/**
 * 统一的可点击修饰符集合。
 *
 * 收敛此前散落各处的三种写法：`pointerInput { detectTapGestures(...) }`（无波纹、长按无 haptic）、
 * 裸 `clickable { }`（默认波纹）与手写的 `clickable(indication = null, interactionSource = remember { ... })`
 * （去波纹样板）。可点击区域统一走这里，保证点击/长按反馈一致。
 */

/**
 * 标准可点击：单击带 Material 波纹；传入 [onLongClick] 时额外支持长按，
 * 长按由 [combinedClickable] 自动触发系统 haptic（震动）。
 *
 * 用于列表卡片、可点击行等主操作区域，取代 `pointerInput { detectTapGestures(...) }`
 * （后者既无波纹也无长按 haptic）。
 */
fun Modifier.appClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
): Modifier =
    if (onLongClick == null) {
        clickable(enabled = enabled, onClick = onClick)
    } else {
        combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
    }

/**
 * 无波纹点击：用于聊天气泡内贴边的小图标（复制/重答/更多/版本切换箭头、思考折叠等）。
 * 这些元素紧贴气泡边缘，波纹会显脏，故统一去掉 indication。
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
 * 取代各处 `scale(if (dragging) 0.95f/0.97f else 1f)` 的瞬间跳变（数值此前也不一致）。
 */
@Composable
fun Modifier.draggableLiftScale(dragging: Boolean): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (dragging) DragLiftScale else 1f,
        label = "dragLiftScale",
    )
    return this.scale(scale)
}
