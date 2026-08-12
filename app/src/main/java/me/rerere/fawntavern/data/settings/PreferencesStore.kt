package me.rerere.fawntavern.data.settings

import android.content.Context

/** 消息导航按钮（顶部/上一条/下一条/底部）的显示模式 */
enum class NavButtonsMode { ALWAYS, ON_SCROLL, NEVER }

/** 全局偏好设置（除颜色模式/语言/字号等独立 Store 之外的所有可配置项） */
data class Preferences(
    // ── 主题设置 ──
    /** 纯色背景：开启时背景用纯黑/纯白，其余表面用半透明灰 */
    val solidBackground: Boolean = false,
    // ── 聊天项显示 ──
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

    // ── 渲染设置 ──
    val mathRendering: Boolean = false,
    val userMarkdown: Boolean = true,
    val thinkingMarkdown: Boolean = true,
    val characterMarkdown: Boolean = true,
    val autoCollapseCode: Boolean = false,
    /** 超过该行数自动折叠代码块 */
    val codeCollapseLines: Int = 5,

    // ── 行为与启动 ──
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

    // ── 触觉反馈 ──
    /** 设置页开关切换时的触觉反馈 */
    val switchHaptic: Boolean = true,
    /** 侧边栏（抽屉）开合触觉反馈 */
    val sidebarHaptic: Boolean = true,
    /** 长按触觉反馈 */
    val longPressHaptic: Boolean = true,
)

/** 偏好设置 — SharedPreferences 持久化（扁平键，默认值取自 [Preferences]） */
object PreferencesStore {
    private const val PREFS = "preferences"

    private const val K_SOLID_BACKGROUND = "solidBackground"
    private const val K_SHOW_USER_AVATAR = "showUserAvatar"
    private const val K_SHOW_USER_NAME = "showUserName"
    private const val K_SHOW_USER_TIMESTAMP = "showUserTimestamp"
    private const val K_SHOW_USER_ACTIONS = "showUserActions"
    private const val K_SHOW_MODEL_ICON = "showModelIcon"
    private const val K_SHOW_MODEL_NAME = "showModelName"
    private const val K_SHOW_MODEL_TIMESTAMP = "showModelTimestamp"
    private const val K_SHOW_TOKEN_STATS = "showTokenStats"
    private const val K_SHOW_TOKEN_USAGE = "showTokenUsage"
    private const val K_SHOW_TOKEN_SPEED = "showTokenSpeed"
    private const val K_SHOW_GENERATION_TIME = "showGenerationTime"
    private const val K_MATH_RENDERING = "mathRendering"
    private const val K_USER_MARKDOWN = "userMarkdown"
    private const val K_THINKING_MARKDOWN = "thinkingMarkdown"
    private const val K_CHARACTER_MARKDOWN = "characterMarkdown"
    private const val K_AUTO_COLLAPSE_CODE = "autoCollapseCode"
    private const val K_CODE_COLLAPSE_LINES = "codeCollapseLines"
    private const val K_AUTO_COLLAPSE_THINKING = "autoCollapseThinking"
    private const val K_CONFIRM_REGENERATE = "confirmRegenerate"
    private const val K_CONFIRM_DELETE_CURRENT_VERSION = "confirmDeleteCurrentVersion"
    private const val K_CONFIRM_DELETE_ALL_VERSIONS = "confirmDeleteAllVersions"
    private const val K_NAV_BUTTONS_MODE = "navButtonsMode"
    private const val K_SHOW_CHAT_LIST_DATE = "showChatListDate"
    private const val K_NEW_CHAT_ON_CHAR_SWITCH = "newChatOnCharSwitch"
    private const val K_NEW_CHAT_ON_DELETE_TOPIC = "newChatOnDeleteTopic"
    private const val K_NEW_CHAT_ON_LAUNCH = "newChatOnLaunch"
    private const val K_ENTER_TO_SEND = "enterToSend"
    private const val K_SWITCH_HAPTIC = "switchHaptic"
    private const val K_SIDEBAR_HAPTIC = "sidebarHaptic"
    private const val K_LONG_PRESS_HAPTIC = "longPressHaptic"

    fun get(context: Context): Preferences {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = Preferences()
        fun navMode(name: String?): NavButtonsMode = try {
            NavButtonsMode.valueOf(name ?: "")
        } catch (_: Exception) { d.navButtonsMode }
        val legacyTokenStats = p.getBoolean(K_SHOW_TOKEN_STATS, false)
        return Preferences(
            solidBackground = p.getBoolean(K_SOLID_BACKGROUND, d.solidBackground),
            showUserAvatar = p.getBoolean(K_SHOW_USER_AVATAR, d.showUserAvatar),
            showUserName = p.getBoolean(K_SHOW_USER_NAME, d.showUserName),
            showUserTimestamp = p.getBoolean(K_SHOW_USER_TIMESTAMP, d.showUserTimestamp),
            showUserActions = p.getBoolean(K_SHOW_USER_ACTIONS, d.showUserActions),
            showModelIcon = p.getBoolean(K_SHOW_MODEL_ICON, d.showModelIcon),
            showModelName = p.getBoolean(K_SHOW_MODEL_NAME, d.showModelName),
            showModelTimestamp = p.getBoolean(K_SHOW_MODEL_TIMESTAMP, d.showModelTimestamp),
            showTokenUsage = p.getBoolean(K_SHOW_TOKEN_USAGE, legacyTokenStats),
            showTokenSpeed = p.getBoolean(K_SHOW_TOKEN_SPEED, legacyTokenStats),
            showGenerationTime = p.getBoolean(K_SHOW_GENERATION_TIME, legacyTokenStats),
            mathRendering = p.getBoolean(K_MATH_RENDERING, d.mathRendering),
            userMarkdown = p.getBoolean(K_USER_MARKDOWN, d.userMarkdown),
            thinkingMarkdown = p.getBoolean(K_THINKING_MARKDOWN, d.thinkingMarkdown),
            characterMarkdown = p.getBoolean(K_CHARACTER_MARKDOWN, d.characterMarkdown),
            autoCollapseCode = p.getBoolean(K_AUTO_COLLAPSE_CODE, d.autoCollapseCode),
            codeCollapseLines = p.getInt(K_CODE_COLLAPSE_LINES, d.codeCollapseLines),
            autoCollapseThinking = p.getBoolean(K_AUTO_COLLAPSE_THINKING, d.autoCollapseThinking),
            confirmRegenerate = p.getBoolean(K_CONFIRM_REGENERATE, d.confirmRegenerate),
            confirmDeleteCurrentVersion = p.getBoolean(K_CONFIRM_DELETE_CURRENT_VERSION, d.confirmDeleteCurrentVersion),
            confirmDeleteAllVersions = p.getBoolean(K_CONFIRM_DELETE_ALL_VERSIONS, d.confirmDeleteAllVersions),
            navButtonsMode = navMode(p.getString(K_NAV_BUTTONS_MODE, null)),
            showChatListDate = p.getBoolean(K_SHOW_CHAT_LIST_DATE, d.showChatListDate),
            newChatOnCharSwitch = p.getBoolean(K_NEW_CHAT_ON_CHAR_SWITCH, d.newChatOnCharSwitch),
            newChatOnDeleteTopic = p.getBoolean(K_NEW_CHAT_ON_DELETE_TOPIC, d.newChatOnDeleteTopic),
            newChatOnLaunch = p.getBoolean(K_NEW_CHAT_ON_LAUNCH, d.newChatOnLaunch),
            enterToSend = p.getBoolean(K_ENTER_TO_SEND, d.enterToSend),
            switchHaptic = p.getBoolean(K_SWITCH_HAPTIC, d.switchHaptic),
            sidebarHaptic = p.getBoolean(K_SIDEBAR_HAPTIC, d.sidebarHaptic),
            longPressHaptic = p.getBoolean(K_LONG_PRESS_HAPTIC, d.longPressHaptic),
        )
    }

    fun set(context: Context, s: Preferences) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_SOLID_BACKGROUND, s.solidBackground)
            .putBoolean(K_SHOW_USER_AVATAR, s.showUserAvatar)
            .putBoolean(K_SHOW_USER_NAME, s.showUserName)
            .putBoolean(K_SHOW_USER_TIMESTAMP, s.showUserTimestamp)
            .putBoolean(K_SHOW_USER_ACTIONS, s.showUserActions)
            .putBoolean(K_SHOW_MODEL_ICON, s.showModelIcon)
            .putBoolean(K_SHOW_MODEL_NAME, s.showModelName)
            .putBoolean(K_SHOW_MODEL_TIMESTAMP, s.showModelTimestamp)
            .putBoolean(K_SHOW_TOKEN_USAGE, s.showTokenUsage)
            .putBoolean(K_SHOW_TOKEN_SPEED, s.showTokenSpeed)
            .putBoolean(K_SHOW_GENERATION_TIME, s.showGenerationTime)
            .putBoolean(K_MATH_RENDERING, s.mathRendering)
            .putBoolean(K_USER_MARKDOWN, s.userMarkdown)
            .putBoolean(K_THINKING_MARKDOWN, s.thinkingMarkdown)
            .putBoolean(K_CHARACTER_MARKDOWN, s.characterMarkdown)
            .putBoolean(K_AUTO_COLLAPSE_CODE, s.autoCollapseCode)
            .putInt(K_CODE_COLLAPSE_LINES, s.codeCollapseLines.coerceIn(1, 999))
            .putBoolean(K_AUTO_COLLAPSE_THINKING, s.autoCollapseThinking)
            .putBoolean(K_CONFIRM_REGENERATE, s.confirmRegenerate)
            .putBoolean(K_CONFIRM_DELETE_CURRENT_VERSION, s.confirmDeleteCurrentVersion)
            .putBoolean(K_CONFIRM_DELETE_ALL_VERSIONS, s.confirmDeleteAllVersions)
            .putString(K_NAV_BUTTONS_MODE, s.navButtonsMode.name)
            .putBoolean(K_SHOW_CHAT_LIST_DATE, s.showChatListDate)
            .putBoolean(K_NEW_CHAT_ON_CHAR_SWITCH, s.newChatOnCharSwitch)
            .putBoolean(K_NEW_CHAT_ON_DELETE_TOPIC, s.newChatOnDeleteTopic)
            .putBoolean(K_NEW_CHAT_ON_LAUNCH, s.newChatOnLaunch)
            .putBoolean(K_ENTER_TO_SEND, s.enterToSend)
            .putBoolean(K_SWITCH_HAPTIC, s.switchHaptic)
            .putBoolean(K_SIDEBAR_HAPTIC, s.sidebarHaptic)
            .putBoolean(K_LONG_PRESS_HAPTIC, s.longPressHaptic)
            .apply()
    }

    /** 只改部分字段：复制当前值，套用 [transform] 后整体写回 */
    fun update(context: Context, transform: (Preferences) -> Preferences) {
        set(context, transform(get(context)))
    }
}
