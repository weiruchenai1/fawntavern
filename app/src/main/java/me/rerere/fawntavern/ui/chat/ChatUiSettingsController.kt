package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.data.settings.FontSizeStore
import me.rerere.fawntavern.data.settings.NavButtonsMode
import me.rerere.fawntavern.data.settings.Preferences
import me.rerere.fawntavern.data.settings.PreferencesStore

internal data class ChatUiSettings(
    val fontScale: Float,
    val showChatBarCharacterName: Boolean,
    val showChatBarModelName: Boolean,
    val showChatBarProvider: Boolean,
    val showUserAvatar: Boolean,
    val showUserName: Boolean,
    val showUserTimestamp: Boolean,
    val showUserActions: Boolean,
    val showModelIcon: Boolean,
    val showModelName: Boolean,
    val showModelTimestamp: Boolean,
    val showTokenUsage: Boolean,
    val showTokenSpeed: Boolean,
    val showGenerationTime: Boolean,
    val htmlCssRendering: Boolean,
    val javascriptSupport: Boolean,
    val mathRendering: Boolean,
    val userMarkdown: Boolean,
    val thinkingMarkdown: Boolean,
    val characterMarkdown: Boolean,
    val autoCollapseCode: Boolean,
    val codeCollapseLines: Int,
    val autoCollapseThinking: Boolean,
    val confirmRegenerate: Boolean,
    val confirmDeleteCurrentVersion: Boolean,
    val confirmDeleteAllVersions: Boolean,
    val navButtonsMode: NavButtonsMode,
    val showChatListDate: Boolean,
    val newChatOnCharSwitch: Boolean,
    val newChatOnDeleteTopic: Boolean,
    val newChatOnLaunch: Boolean,
    val enterToSend: Boolean,
    val sidebarHaptic: Boolean,
    val longPressHaptic: Boolean,
)

internal interface ChatUiSettingsDataSource {
    fun preferences(): Preferences
    fun fontScale(): Float
}

internal class AndroidChatUiSettingsDataSource(
    private val context: Context,
) : ChatUiSettingsDataSource {
    override fun preferences(): Preferences = PreferencesStore.get(context)

    override fun fontScale(): Float = FontSizeStore.getScale(context)
}

/** 将聊天页需要的设置投影为一次性快照，避免 Compose 页面直接访问持久化层。 */
internal class ChatUiSettingsController(
    private val dataSource: ChatUiSettingsDataSource,
) {
    fun load(): ChatUiSettings {
        val prefs = dataSource.preferences()
        return ChatUiSettings(
            fontScale = dataSource.fontScale(),
            showChatBarCharacterName = prefs.showChatBarCharacterName,
            showChatBarModelName = prefs.showChatBarModelName,
            showChatBarProvider = prefs.showChatBarProvider,
            showUserAvatar = prefs.showUserAvatar,
            showUserName = prefs.showUserName,
            showUserTimestamp = prefs.showUserTimestamp,
            showUserActions = prefs.showUserActions,
            showModelIcon = prefs.showModelIcon,
            showModelName = prefs.showModelName,
            showModelTimestamp = prefs.showModelTimestamp,
            showTokenUsage = prefs.showTokenUsage,
            showTokenSpeed = prefs.showTokenSpeed,
            showGenerationTime = prefs.showGenerationTime,
            htmlCssRendering = prefs.htmlCssRendering,
            javascriptSupport = prefs.javascriptSupport,
            mathRendering = prefs.mathRendering,
            userMarkdown = prefs.userMarkdown,
            thinkingMarkdown = prefs.thinkingMarkdown,
            characterMarkdown = prefs.characterMarkdown,
            autoCollapseCode = prefs.autoCollapseCode,
            codeCollapseLines = prefs.codeCollapseLines,
            autoCollapseThinking = prefs.autoCollapseThinking,
            confirmRegenerate = prefs.confirmRegenerate,
            confirmDeleteCurrentVersion = prefs.confirmDeleteCurrentVersion,
            confirmDeleteAllVersions = prefs.confirmDeleteAllVersions,
            navButtonsMode = prefs.navButtonsMode,
            showChatListDate = prefs.showChatListDate,
            newChatOnCharSwitch = prefs.newChatOnCharSwitch,
            newChatOnDeleteTopic = prefs.newChatOnDeleteTopic,
            newChatOnLaunch = prefs.newChatOnLaunch,
            enterToSend = prefs.enterToSend,
            sidebarHaptic = prefs.sidebarHaptic,
            longPressHaptic = prefs.longPressHaptic,
        )
    }
}
