package me.rerere.fawntavern.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.ChevronsDown
import com.composables.icons.lucide.ChevronsUp
import com.composables.icons.lucide.Lucide
import me.rerere.fawntavern.R
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.Space8

/**
 * 悬浮滚动导航按钮栏：竖排四个无底色圆钮（跳到顶部 / 上一条 / 下一条 / 回到底部），
 * 贴在聊天区右下角。
 *
 * 可见性由 [ChatScrollController.showNavButtons] 驱动——滚动时出现，静止两秒后自动隐藏。
 */
@Composable
fun ScrollNavButtons(
    visible: Boolean,
    onScrollToTop: () -> Unit,
    onPreviousMessage: () -> Unit,
    onNextMessage: () -> Unit,
    onScrollToBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        // 滑出比整栏宽度多两成，确保完全离场而不是贴着边缘留一条
        enter = slideInHorizontally(tween(SlideDurationMs, easing = EaseOutCubic)) {
            (it * SlideOvershoot).toInt()
        } + fadeIn(tween(FadeDurationMs, easing = EaseOutCubic)),
        exit = slideOutHorizontally(tween(SlideDurationMs, easing = EaseInCubic)) {
            (it * SlideOvershoot).toInt()
        } + fadeOut(tween(FadeDurationMs, easing = EaseInCubic)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space8)) {
            GlassyNavButton(Lucide.ChevronsUp, R.string.scroll_to_top, onScrollToTop)
            GlassyNavButton(Lucide.ChevronUp, R.string.previous_message, onPreviousMessage)
            GlassyNavButton(Lucide.ChevronDown, R.string.next_message, onNextMessage)
            GlassyNavButton(Lucide.ChevronsDown, R.string.scroll_to_bottom, onScrollToBottom)
        }
    }
}

/** 单个导航圆钮：无底色，只留描边界定范围；按下时由 [AppIconButton] 显示圆形波纹。 */
@Composable
private fun GlassyNavButton(icon: ImageVector, contentDescription: Int, onClick: () -> Unit) {
    AppIconButton(
        icon = icon,
        contentDescription = stringResource(contentDescription),
        onClick = onClick,
        modifier = Modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.20f),
            shape = CircleShape,
        ),
        tint = MaterialTheme.colorScheme.onSurface,
        container = Color.Transparent,
        size = ButtonDiameter,
        iconSize = IconSize,
    )
}

private val ButtonDiameter = 28.dp
private val IconSize = 16.dp
private const val SlideDurationMs = 280
private const val FadeDurationMs = 220
private const val SlideOvershoot = 1.2f
