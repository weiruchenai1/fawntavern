package me.rerere.fawntavern.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.settings.Preferences
import me.rerere.fawntavern.data.settings.PreferencesStore
import me.rerere.fawntavern.ui.components.SettingsSubPage

/** 二级页外壳：返回键 + 居中标题 + 可滚动内容，布局统一走 [SettingsSubPage] */
@Composable
private fun PrefSubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    SettingsSubPage(title, onBack) {
        content()
    }
}

/** 主题设置：纯色背景开关 */
@Composable
internal fun ThemeSettingsScreen(
    onBack: () -> Unit,
    solidBackground: Boolean,
    onSolidBackgroundChange: (Boolean) -> Unit,
) {
    PrefSubPage(stringResource(R.string.theme_settings), onBack) {
        PrefSection(stringResource(R.string.solid_background)) {
            PrefToggle(
                icon = null,
                label = stringResource(R.string.solid_background),
                desc = stringResource(R.string.solid_background_desc),
                checked = solidBackground,
                onCheckedChange = onSolidBackgroundChange,
            )
        }
    }
}

/** 聊天项显示：用户/模型各元素的显隐开关 */
@Composable
internal fun ChatItemDisplayScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(PreferencesStore.get(context)) }
    fun save(next: Preferences) {
        prefs = next
        PreferencesStore.set(context, next)
    }
    PrefSubPage(stringResource(R.string.chat_item_display), onBack) {
        PrefSection(stringResource(R.string.chat_item_display)) {
            PrefToggle(PrefIconCircleUser, stringResource(R.string.show_user_avatar), prefs.showUserAvatar) { save(prefs.copy(showUserAvatar = it)) }
            PrefToggle(PrefIconType, stringResource(R.string.show_user_name), prefs.showUserName) { save(prefs.copy(showUserName = it)) }
            PrefToggle(PrefIconClock, stringResource(R.string.show_user_timestamp), prefs.showUserTimestamp) { save(prefs.copy(showUserTimestamp = it)) }
            PrefToggle(PrefIconEllipsis, stringResource(R.string.show_user_actions), prefs.showUserActions) { save(prefs.copy(showUserActions = it)) }
            PrefToggle(PrefIconBot, stringResource(R.string.show_model_icon), prefs.showModelIcon) { save(prefs.copy(showModelIcon = it)) }
            PrefToggle(PrefIconTag, stringResource(R.string.show_model_name), prefs.showModelName) { save(prefs.copy(showModelName = it)) }
            PrefToggle(PrefIconClock, stringResource(R.string.show_model_timestamp), prefs.showModelTimestamp) { save(prefs.copy(showModelTimestamp = it)) }
            PrefToggle(PrefIconCalculator, stringResource(R.string.show_token_stats), prefs.showTokenStats) { save(prefs.copy(showTokenStats = it)) }
        }
    }
}

/** 渲染设置：markdown / 数学 / 代码块折叠 */
@Composable
internal fun RenderingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(PreferencesStore.get(context)) }
    fun save(next: Preferences) {
        prefs = next
        PreferencesStore.set(context, next)
    }
    PrefSubPage(stringResource(R.string.rendering_settings), onBack) {
        PrefSection(stringResource(R.string.rendering_settings)) {
            PrefToggle(PrefIconSigma, stringResource(R.string.math_rendering),
                prefs.mathRendering, desc = stringResource(R.string.math_rendering_desc)) { save(prefs.copy(mathRendering = it)) }
            PrefToggle(PrefIconType, stringResource(R.string.user_markdown), prefs.userMarkdown) { save(prefs.copy(userMarkdown = it)) }
            PrefToggle(PrefIconBrain, stringResource(R.string.thinking_markdown), prefs.thinkingMarkdown) { save(prefs.copy(thinkingMarkdown = it)) }
            PrefToggle(PrefIconMessageSquareText, stringResource(R.string.character_markdown), prefs.characterMarkdown) { save(prefs.copy(characterMarkdown = it)) }
            PrefToggle(PrefIconFold, stringResource(R.string.auto_collapse_code), prefs.autoCollapseCode) { save(prefs.copy(autoCollapseCode = it)) }
            if (prefs.autoCollapseCode) {
                PrefLineStepper(
                    icon = PrefIconRows,
                    label = stringResource(R.string.auto_collapse_code_lines),
                    value = prefs.codeCollapseLines,
                    onMinus = { save(prefs.copy(codeCollapseLines = (prefs.codeCollapseLines - 1).coerceAtLeast(1))) },
                    onPlus = { save(prefs.copy(codeCollapseLines = (prefs.codeCollapseLines + 1).coerceAtMost(999))) },
                )
            }
        }
    }
}

/** 行为与启动：思考折叠 / 重生成确认 / 导航按钮 / 新建对话 / 回车发送 */
@Composable
internal fun BehaviorStartupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(PreferencesStore.get(context)) }
    fun save(next: Preferences) {
        prefs = next
        PreferencesStore.set(context, next)
    }
    PrefSubPage(stringResource(R.string.behavior_startup), onBack) {
        PrefSection(stringResource(R.string.behavior_startup)) {
            PrefToggle(PrefIconFold, stringResource(R.string.auto_collapse_thinking), prefs.autoCollapseThinking) { save(prefs.copy(autoCollapseThinking = it)) }
            PrefToggle(PrefIconShield, stringResource(R.string.confirm_regenerate), prefs.confirmRegenerate) { save(prefs.copy(confirmRegenerate = it)) }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(PrefIconNav, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.message_nav_buttons),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
                NavButtonsSegmented(
                    mode = prefs.navButtonsMode,
                    onSelect = { save(prefs.copy(navButtonsMode = it)) },
                )
            }
            PrefToggle(PrefIconCalendar, stringResource(R.string.show_chat_list_date), prefs.showChatListDate) { save(prefs.copy(showChatListDate = it)) }
            PrefToggle(PrefIconUserPlus, stringResource(R.string.new_chat_on_char_switch), prefs.newChatOnCharSwitch) { save(prefs.copy(newChatOnCharSwitch = it)) }
            PrefToggle(PrefIconMsgPlus, stringResource(R.string.new_chat_on_delete_topic), prefs.newChatOnDeleteTopic) { save(prefs.copy(newChatOnDeleteTopic = it)) }
            PrefToggle(PrefIconRocket, stringResource(R.string.new_chat_on_launch), prefs.newChatOnLaunch) { save(prefs.copy(newChatOnLaunch = it)) }
            PrefToggle(PrefIconEnter, stringResource(R.string.enter_to_send), prefs.enterToSend) { save(prefs.copy(enterToSend = it)) }
        }
    }
}

/** 触觉反馈：开关 / 侧边栏 / 长按 */
@Composable
internal fun HapticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(PreferencesStore.get(context)) }
    fun save(next: Preferences) {
        prefs = next
        PreferencesStore.set(context, next)
    }
    PrefSubPage(stringResource(R.string.haptics), onBack) {
        PrefSection(stringResource(R.string.haptics)) {
            PrefToggle(PrefIconVibrate, stringResource(R.string.switch_haptic), prefs.switchHaptic) { save(prefs.copy(switchHaptic = it)) }
            PrefToggle(PrefIconPanel, stringResource(R.string.sidebar_haptic), prefs.sidebarHaptic) { save(prefs.copy(sidebarHaptic = it)) }
            PrefToggle(PrefIconHand, stringResource(R.string.long_press_haptic), prefs.longPressHaptic) { save(prefs.copy(longPressHaptic = it)) }
        }
    }
}
