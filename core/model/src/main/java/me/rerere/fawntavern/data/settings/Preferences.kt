package me.rerere.fawntavern.data.settings

/** 消息导航按钮的显示模式。 */
enum class NavButtonsMode { ALWAYS, ON_SCROLL, NEVER }

/** 全局偏好设置，不包含颜色模式、语言和字号等独立设置。 */
data class Preferences(
    val solidBackground: Boolean = false,
    val showChatBarCharacterName: Boolean = true,
    val showChatBarModelName: Boolean = true,
    val showChatBarProvider: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showUserName: Boolean = true,
    val showUserTimestamp: Boolean = false,
    val showUserActions: Boolean = true,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showModelTimestamp: Boolean = false,
    val showTokenUsage: Boolean = false,
    val showTokenSpeed: Boolean = false,
    val showGenerationTime: Boolean = false,
    val htmlCssRendering: Boolean = true,
    val javascriptSupport: Boolean = false,
    val mathRendering: Boolean = false,
    val userMarkdown: Boolean = true,
    val thinkingMarkdown: Boolean = true,
    val characterMarkdown: Boolean = true,
    val autoCollapseCode: Boolean = false,
    val codeCollapseLines: Int = 5,
    val autoCollapseThinking: Boolean = true,
    val confirmRegenerate: Boolean = false,
    val confirmDeleteCurrentVersion: Boolean = true,
    val confirmDeleteAllVersions: Boolean = true,
    val navButtonsMode: NavButtonsMode = NavButtonsMode.ON_SCROLL,
    val showChatListDate: Boolean = false,
    val newChatOnCharSwitch: Boolean = false,
    val newChatOnDeleteTopic: Boolean = false,
    val newChatOnLaunch: Boolean = true,
    val enterToSend: Boolean = false,
    val switchHaptic: Boolean = true,
    val sidebarHaptic: Boolean = true,
    val longPressHaptic: Boolean = true,
)
