package me.rerere.fawntavern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一的图标按钮：图标居中于一个圆形（圆角 = 全圆，等价「圆角 999」）的可点击区域。
 *
 * - [container] 透明（默认）时：平时无背景，**点击时才显示圆形按压背景**（Material 波纹被裁成圆形）。
 *   取代裸 `Icon(...).size(x).clickable { }`——后者没有 clip，波纹是难看的方块。
 * - [container] 传入颜色时：图标外**常驻一层圆形背景**（如侧边抽屉底部的角色/设置导航图标）。
 *
 * [size] 是可点击圆的直径（触摸目标），[iconSize] 是图标本身大小。
 */
@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color = Color.Transparent,
    size: Dp = 40.dp,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = tint)
    }
}
