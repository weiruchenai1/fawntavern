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
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Calculator
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.CircleUser
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.CornerDownLeft
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.FoldVertical
import com.composables.icons.lucide.Hand
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquarePlus
import com.composables.icons.lucide.MessageSquareText
import com.composables.icons.lucide.Navigation
import com.composables.icons.lucide.PanelLeft
import com.composables.icons.lucide.Rocket
import com.composables.icons.lucide.Rows3
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Sigma
import com.composables.icons.lucide.Tag
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.UserPlus
import com.composables.icons.lucide.Vibrate
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.settings.Preferences
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
    val controller = remember(context) { SettingsDataController(AndroidSettingsDataSource(context)) }
    var prefs by remember(controller) { mutableStateOf(controller.preferences()) }
    fun save(next: Preferences) {
        prefs = controller.savePreferences(next)
    }
    PrefSubPage(stringResource(R.string.chat_item_display), onBack) {
        PrefSection(stringResource(R.string.chat_item_display)) {
            PrefToggle(Lucide.CircleUser, stringResource(R.string.show_user_avatar), prefs.showUserAvatar) { save(prefs.copy(showUserAvatar = it)) }
            PrefToggle(Lucide.Type, stringResource(R.string.show_user_name), prefs.showUserName) { save(prefs.copy(showUserName = it)) }
            PrefToggle(Lucide.Clock, stringResource(R.string.show_user_timestamp), prefs.showUserTimestamp) { save(prefs.copy(showUserTimestamp = it)) }
            PrefToggle(Lucide.EllipsisVertical, stringResource(R.string.show_user_actions), prefs.showUserActions) { save(prefs.copy(showUserActions = it)) }
            PrefToggle(Lucide.Bot, stringResource(R.string.show_model_icon), prefs.showModelIcon) { save(prefs.copy(showModelIcon = it)) }
            PrefToggle(Lucide.Tag, stringResource(R.string.show_model_name), prefs.showModelName) { save(prefs.copy(showModelName = it)) }
            PrefToggle(Lucide.Clock, stringResource(R.string.show_model_timestamp), prefs.showModelTimestamp) { save(prefs.copy(showModelTimestamp = it)) }
            PrefToggle(Lucide.Calculator, stringResource(R.string.show_token_usage), prefs.showTokenUsage) { save(prefs.copy(showTokenUsage = it)) }
            PrefToggle(Lucide.Calculator, stringResource(R.string.show_token_speed), prefs.showTokenSpeed) { save(prefs.copy(showTokenSpeed = it)) }
            PrefToggle(Lucide.Clock, stringResource(R.string.show_generation_time), prefs.showGenerationTime) { save(prefs.copy(showGenerationTime = it)) }
        }
    }
}

/** 渲染设置：markdown / 数学 / 代码块折叠 */
@Composable
internal fun RenderingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { SettingsDataController(AndroidSettingsDataSource(context)) }
    var prefs by remember(controller) { mutableStateOf(controller.preferences()) }
    fun save(next: Preferences) {
        prefs = controller.savePreferences(next)
    }
    PrefSubPage(stringResource(R.string.rendering_settings), onBack) {
        PrefSection(stringResource(R.string.rendering_settings)) {
            PrefToggle(Lucide.Sigma, stringResource(R.string.math_rendering),
                prefs.mathRendering, desc = stringResource(R.string.math_rendering_desc)) { save(prefs.copy(mathRendering = it)) }
            PrefToggle(Lucide.Type, stringResource(R.string.user_markdown), prefs.userMarkdown) { save(prefs.copy(userMarkdown = it)) }
            PrefToggle(Lucide.Brain, stringResource(R.string.thinking_markdown), prefs.thinkingMarkdown) { save(prefs.copy(thinkingMarkdown = it)) }
            PrefToggle(Lucide.MessageSquareText, stringResource(R.string.character_markdown), prefs.characterMarkdown) { save(prefs.copy(characterMarkdown = it)) }
            PrefToggle(Lucide.FoldVertical, stringResource(R.string.auto_collapse_code), prefs.autoCollapseCode) { save(prefs.copy(autoCollapseCode = it)) }
            if (prefs.autoCollapseCode) {
                PrefLineInput(
                    icon = Lucide.Rows3,
                    label = stringResource(R.string.auto_collapse_code_lines),
                    value = prefs.codeCollapseLines,
                    onValueChange = { save(prefs.copy(codeCollapseLines = it)) },
                )
            }
        }
    }
}

/** 行为与启动：思考折叠 / 重生成确认 / 导航按钮 / 新建对话 / 回车发送 */
@Composable
internal fun BehaviorStartupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { SettingsDataController(AndroidSettingsDataSource(context)) }
    var prefs by remember(controller) { mutableStateOf(controller.preferences()) }
    fun save(next: Preferences) {
        prefs = controller.savePreferences(next)
    }
    PrefSubPage(stringResource(R.string.behavior_startup), onBack) {
        PrefSection(stringResource(R.string.behavior_startup)) {
            PrefToggle(Lucide.FoldVertical, stringResource(R.string.auto_collapse_thinking), prefs.autoCollapseThinking) { save(prefs.copy(autoCollapseThinking = it)) }
            PrefToggle(Lucide.ShieldCheck, stringResource(R.string.confirm_regenerate), prefs.confirmRegenerate) { save(prefs.copy(confirmRegenerate = it)) }
            PrefToggle(Lucide.ShieldCheck, stringResource(R.string.confirm_delete_current_version), prefs.confirmDeleteCurrentVersion) { save(prefs.copy(confirmDeleteCurrentVersion = it)) }
            PrefToggle(Lucide.ShieldCheck, stringResource(R.string.confirm_delete_all_versions), prefs.confirmDeleteAllVersions) { save(prefs.copy(confirmDeleteAllVersions = it)) }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Lucide.Navigation, null, Modifier.size(20.dp),
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
            PrefToggle(Lucide.Calendar, stringResource(R.string.show_chat_list_date), prefs.showChatListDate) { save(prefs.copy(showChatListDate = it)) }
            PrefToggle(Lucide.UserPlus, stringResource(R.string.new_chat_on_char_switch), prefs.newChatOnCharSwitch) { save(prefs.copy(newChatOnCharSwitch = it)) }
            PrefToggle(Lucide.MessageSquarePlus, stringResource(R.string.new_chat_on_delete_topic), prefs.newChatOnDeleteTopic) { save(prefs.copy(newChatOnDeleteTopic = it)) }
            PrefToggle(Lucide.Rocket, stringResource(R.string.new_chat_on_launch), prefs.newChatOnLaunch) { save(prefs.copy(newChatOnLaunch = it)) }
            PrefToggle(Lucide.CornerDownLeft, stringResource(R.string.enter_to_send), prefs.enterToSend) { save(prefs.copy(enterToSend = it)) }
        }
    }
}

/** 触觉反馈：开关 / 侧边栏 / 长按 */
@Composable
internal fun HapticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { SettingsDataController(AndroidSettingsDataSource(context)) }
    var prefs by remember(controller) { mutableStateOf(controller.preferences()) }
    fun save(next: Preferences) {
        prefs = controller.savePreferences(next)
    }
    PrefSubPage(stringResource(R.string.haptics), onBack) {
        PrefSection(stringResource(R.string.haptics)) {
            PrefToggle(Lucide.Vibrate, stringResource(R.string.switch_haptic), prefs.switchHaptic) { save(prefs.copy(switchHaptic = it)) }
            PrefToggle(Lucide.PanelLeft, stringResource(R.string.sidebar_haptic), prefs.sidebarHaptic) { save(prefs.copy(sidebarHaptic = it)) }
            PrefToggle(Lucide.Hand, stringResource(R.string.long_press_haptic), prefs.longPressHaptic) { save(prefs.copy(longPressHaptic = it)) }
        }
    }
}
