package me.rerere.fawntavern.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquareText
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Rocket
import com.composables.icons.lucide.Sigma
import com.composables.icons.lucide.Vibrate
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.settings.NavButtonsMode
import me.rerere.fawntavern.data.settings.Preferences
import me.rerere.fawntavern.ui.components.SettingsSubPage
import me.rerere.fawntavern.ui.components.noRippleClickable
import me.rerere.fawntavern.ui.components.vibrate

/** 偏好设置的二级页面 */
private enum class PrefPage { THEME, CHAT_DISPLAY, RENDERING, BEHAVIOR, HAPTICS }

/**
 * 偏好设置首页：五个分组各自是独立的二级页面（主题设置 / 聊天项显示 / 渲染设置 /
 * 行为与启动 / 触觉反馈），本组件只做列表 + 内部返回栈切换。
 */
@Composable
fun PreferencesScreen(
    onBack: () -> Unit,
    solidBackground: Boolean,
    onSolidBackgroundChange: (Boolean) -> Unit,
) {
    var page by remember { mutableStateOf<PrefPage?>(null) }
    BackHandler { if (page != null) page = null else onBack() }

    when (page) {
        null -> PrefHomeScreen(onBack = onBack, onOpen = { page = it })
        PrefPage.THEME -> ThemeSettingsScreen(
            onBack = { page = null },
            solidBackground = solidBackground,
            onSolidBackgroundChange = onSolidBackgroundChange,
        )
        PrefPage.CHAT_DISPLAY -> ChatItemDisplayScreen(onBack = { page = null })
        PrefPage.RENDERING -> RenderingSettingsScreen(onBack = { page = null })
        PrefPage.BEHAVIOR -> BehaviorStartupScreen(onBack = { page = null })
        PrefPage.HAPTICS -> HapticsScreen(onBack = { page = null })
    }
}

/** 偏好设置首页：二级页入口列表 */
@Composable
private fun PrefHomeScreen(
    onBack: () -> Unit,
    onOpen: (PrefPage) -> Unit,
) {
    SettingsSubPage(stringResource(R.string.preferences), onBack, spacing = 8.dp, scrollable = false) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            PrefNavRow(Lucide.Palette, stringResource(R.string.theme_settings)) { onOpen(PrefPage.THEME) }
            PrefNavRow(Lucide.MessageSquareText, stringResource(R.string.chat_item_display)) { onOpen(PrefPage.CHAT_DISPLAY) }
            PrefNavRow(Lucide.Sigma, stringResource(R.string.rendering_settings)) { onOpen(PrefPage.RENDERING) }
            PrefNavRow(Lucide.Rocket, stringResource(R.string.behavior_startup)) { onOpen(PrefPage.BEHAVIOR) }
            PrefNavRow(Lucide.Vibrate, stringResource(R.string.haptics)) { onOpen(PrefPage.HAPTICS) }
        }
    }
}

/** 二级页入口行：图标 + 标签 + 右箭头 */
@Composable
private fun PrefNavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
        Icon(Lucide.ChevronRight, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 偏好设置分组容器：标题 + 圆角卡片（复用设置页样式） */
@Composable
internal fun PrefSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            content()
        }
    }
}

/** 图标 + 标签 +（可选描述）+ 右侧开关的开关行。desc 放 onCheckedChange 前面，
 *  保证调用处尾随 lambda（{ save(...) }）正确绑定到 onCheckedChange。 */
@Composable
internal fun PrefToggle(
    icon: ImageVector?,
    label: String,
    checked: Boolean,
    desc: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val controller = remember(context) { SettingsDataController(AndroidSettingsDataSource(context)) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            if (desc != null) {
                Text(desc, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = { new ->
                // 开关触觉反馈：读旧值决定这次切换是否给反馈（切掉自己那次不反馈）
                if (controller.switchHapticEnabled()) {
                    vibrate(context)
                }
                onCheckedChange(new)
            },
        )
    }
}

/** 自动折叠代码块的行数阈值：支持按钮步进和直接输入。 */
@Composable
internal fun PrefLineInput(
    icon: ImageVector,
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StepBtn(Lucide.Minus) {
                onValueChange((value - 1).coerceAtLeast(1))
            }
            BasicTextField(
                value = text,
                onValueChange = { input ->
                    val digits = input.filter(Char::isDigit).take(3)
                    text = digits
                    digits.toIntOrNull()?.let { onValueChange(it.coerceIn(1, 999)) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(36.dp).onFocusChanged { state ->
                    if (!state.isFocused) {
                        val normalized = text.toIntOrNull()?.coerceIn(1, 999) ?: value
                        text = normalized.toString()
                        if (normalized != value) onValueChange(normalized)
                    }
                },
                textStyle = MaterialTheme.typography.titleSmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
            )
            StepBtn(Lucide.Plus) {
                onValueChange((value + 1).coerceAtMost(999))
            }
        }
    }
}
@Composable
private fun StepBtn(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .noRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** 消息导航按钮模式的三段选择器：选中段以背景色区分，点击无波纹（noRippleClickable）。 */
@Composable
internal fun NavButtonsSegmented(
    mode: NavButtonsMode,
    onSelect: (NavButtonsMode) -> Unit,
) {
    val options = listOf(
        NavButtonsMode.ALWAYS to stringResource(R.string.nav_always),
        NavButtonsMode.ON_SCROLL to stringResource(R.string.nav_on_scroll),
        NavButtonsMode.NEVER to stringResource(R.string.nav_never),
    )
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(2.dp),
    ) {
        options.forEach { (m, label) ->
            val sel = m == mode
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (sel) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (sel) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .noRippleClickable { onSelect(m) }
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
