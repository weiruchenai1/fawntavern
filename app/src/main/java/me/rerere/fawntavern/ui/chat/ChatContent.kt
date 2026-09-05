package me.rerere.fawntavern.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import me.rerere.fawntavern.ui.components.FloatingWindow
import me.rerere.fawntavern.ui.components.rememberInteractiveDrawerState
import me.rerere.fawntavern.ui.components.InteractiveDrawer
import me.rerere.fawntavern.ui.components.rememberModelSelectorState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.domain.chat.mergeMessageWindow
import me.rerere.fawntavern.domain.chat.settledOverlayTimestamps
import me.rerere.fawntavern.data.settings.NavButtonsMode
import me.rerere.fawntavern.data.settings.ThemeMode
import me.rerere.fawntavern.ui.hooks.ImeLazyListAutoScroller
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.components.vibrate

private typealias Screen = ChatDestination

/**
 * Paging 首屏到达前，只有小会话可以用内存快照立即展示。大历史若回退到完整快照，会瞬间
 * 组合数百个消息项（以及其中的 WebView），使 Paging 失去意义。
 */
private const val ImmediateMessageFallbackLimit = 60

internal fun pagedMessageWindow(
    paged: List<ChatMessage>,
    inMemory: List<ChatMessage>,
): List<ChatMessage> = paged.ifEmpty {
    inMemory.takeIf { it.size <= ImmediateMessageFallbackLimit } ?: emptyList()
}

internal fun messageIndexesByTimestamp(
    messages: List<ChatMessage>,
    offset: Int = 0,
): Map<Long, Int> = messages.mapIndexed { index, message -> message.ts to (offset + index) }.toMap()

internal fun messageIndexesForWindow(
    messages: List<ChatMessage>,
    allTimestamps: List<Long>,
    fallbackOffset: Int,
): Map<Long, Int> = messages.mapIndexed { index, message ->
    val persistedIndex = allTimestamps.binarySearch(message.ts)
    message.ts to if (persistedIndex >= 0) persistedIndex else fallbackOffset + index
}.toMap()

@Composable
internal fun ChatContent(
    state: ChatUiState,
    inputState: TextFieldState,
    pagedMessages: Flow<PagingData<ChatMessage>>,
    effects: Flow<ChatEffect>,
    frontendEvents: Flow<ChatFrontendEvent>,
    onAction: (ChatAction) -> Unit,
    frontendRpc: FrontendRpcCall = { method, _ -> error("Frontend RPC method is unavailable: $method") },
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    solidBackground: Boolean = false,
    onSolidBackgroundChange: (Boolean) -> Unit = {},
    startAtSettings: Boolean = false,
) {
    val conversation = state.conversation
    val input = state.input
    val generation = state.generation
    val profile = state.profile
    val model = state.model
    val search = state.search
    val drawerState = rememberInteractiveDrawerState()
    val scope = rememberCoroutineScope()
    // ── 页面返回栈（纯 UI 状态） ──
    val nav = rememberChatNavigationStack(startAtSettings)
    fun navBack() { nav.removeLastOrNull() }
    val overlayState = rememberChatOverlayState()
    val modelSelector = rememberModelSelectorState(model.displaySpec ?: "", model.apiConfig.providers)
    var scrollToBottomTrigger by remember { mutableIntStateOf(0) }

    val ctx = LocalContext.current
    val media = rememberChatMediaActions(onAction)
    val resources = LocalResources.current
    ChatFrontendEvents(frontendEvents)
    val keyboardController = LocalSoftwareKeyboardController.current

    // One-shot UI work is emitted by the ViewModel and consumed only at this route boundary.
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is ChatEffect.ShowMessage -> Toast.makeText(
                    ctx,
                    effect.text,
                    if (effect.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
                ChatEffect.OpenModelSelector -> modelSelector.open()
                ChatEffect.ScrollToBottom -> scrollToBottomTrigger++
                ChatEffect.HideKeyboard -> keyboardController?.hide()
            }
        }
    }
    // 抽屉里可能改了用户名/头像，关抽屉时刷新
    LaunchedEffect(drawerState.isOpen) {
        if (!drawerState.isOpen) onAction(ChatAction.ReloadUserProfile)
    }
    // 打开抽屉（按钮或边缘手势）即收起键盘
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.isOpen }.collect {
            if (it) keyboardController?.hide()
        }
    }

    // ── 滚动状态提升到全屏页面切换上方 ──
    // 全屏页面（设置/角色列表等）通过 when 分支 + return 实现，命中时整个聊天区域离开组合，
    // 其内所有 remember 状态被销毁。返回时从零重建 → 滚动位置丢失 + LaunchedEffect 误触钉底。
    // 把滚动状态机提升到 when 上方，使其存活在 ChatScreen 作用域内，不受 when 分支切换影响。
    val scrollCtrl = rememberChatScrollController()
    // 记录上次因为"开/切会话"钉底的 session id，切换全屏页返回不触发重钉
    var lastPinnedSessionId by remember { mutableStateOf<String?>(null) }
    var firstPagePinnedSessionId by remember { mutableStateOf<String?>(null) }

    // ── TTS 悬浮窗：挂在 App 窗口之上，朗读时悬浮于屏幕任意页面；初始位置在顶栏下方左缘 ──
    val density = LocalDensity.current
    val ttsBarOffset = with(density) {
        Offset(Space16.toPx(), WindowInsets.statusBars.getTop(this).toFloat() + 48.dp.toPx())
    }
    FloatingWindow(
        tag = "tts_controller",
        themeMode = themeMode,
        visibility = profile.tts.speaking,
        initialOffsetX = ttsBarOffset.x,
        initialOffsetY = ttsBarOffset.y,
    ) {
        TtsFloatingBar(
            state = profile.tts,
            onPause = { onAction(ChatAction.PauseSpeaking) },
            onResume = { onAction(ChatAction.ResumeSpeaking) },
            onStop = { onAction(ChatAction.StopSpeaking) },
            onFastForward = { onAction(ChatAction.FastForwardSpeaking) },
            onCycleSpeed = { onAction(ChatAction.CycleSpeakingSpeed) },
        )
    }

    // ── 全屏页面：渲染栈顶 ──
    // SaveableStateProvider 包裹每个分支：从 Settings 进入 Characters 再返回时，
    // Settings 的 ScrollState 被暂存→恢复；否则 Settings 离开组合后重建，滚动回到顶部。
    val screenStateHolder = rememberSaveableStateHolder()
    val activeDestination = nav.lastOrNull()
    if (activeDestination != null) {
        ChatDestinationHost(
            destination = activeDestination,
            stateHolder = screenStateHolder,
            state = state,
            onAction = onAction,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            solidBackground = solidBackground,
            onSolidBackgroundChange = onSolidBackgroundChange,
            onBack = ::navBack,
            onNavigate = nav::add,
            onOpenSearchSession = { id ->
                onAction(ChatAction.OpenSession(id))
                drawerState.snapClose()
                navBack()
            },
        )
        return
    }

    model.revision
    val displaySpec = model.displaySpec
    val displayProvId = displaySpec?.substringBefore("::") ?: ""
    val displayModelId = displaySpec?.substringAfter("::", "") ?: ""
    val displayProv = if (displaySpec != null) model.apiConfig.providers.find { it.id == displayProvId && it.enabled } else null

    // 偏好和字号由 ViewModel 提供；设置页返回时统一刷新快照。
    val prefs = state.settings
    val fontScale = prefs.fontScale
    val chatBarTitle = if (prefs.showChatBarCharacterName) {
        (conversation.card?.name ?: conversation.current?.charName ?: "")
            .ifBlank { stringResource(R.string.default_character) }
    } else {
        null
    }
    val chatBarSubtitle = if (!prefs.showChatBarModelName) {
        null
    } else if (displaySpec == null) {
        stringResource(R.string.no_model_selected)
    } else {
        val modelName = displayProv?.model(displayModelId)?.name
            ?.ifBlank { displayModelId }
            ?: displayModelId
        val providerName = displayProv?.name.orEmpty()
        if (prefs.showChatBarProvider && providerName.isNotBlank()) {
            "$modelName ($providerName)"
        } else {
            modelName
        }
    }
    val renderPrefs = RenderPrefs(
        markdown = prefs.characterMarkdown,
        htmlCss = prefs.htmlCssRendering,
        javascript = prefs.javascriptSupport,
        math = prefs.mathRendering,
        autoCollapseCode = prefs.autoCollapseCode,
        codeCollapseLines = prefs.codeCollapseLines,
    )
    // 侧边栏触觉反馈：抽屉打开时给一次短震动（走 Vibrator，独立于长按触觉闸门）
    LaunchedEffect(drawerState.isOpen) {
        if (prefs.sidebarHaptic && drawerState.isOpen) {
            vibrate(ctx)
        }
    }

    Box(Modifier.fillMaxSize()) {
        InteractiveDrawer(
            state = drawerState,
            drawerContent = {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                ChatDrawerContent(
                    onClose = { scope.launch { drawerState.close() } },
                    // 从抽屉进入的页面/弹层不关抽屉：返回（或收起面板）后仍停在抽屉
                    onSettings = { nav.add(Screen.Settings) },
                    onTranslator = { nav.add(Screen.Translator) },
                    onStatistics = { nav.add(Screen.Statistics) },
                    onCharSelect = { overlayState.showCharPicker = true },
                    onCharList = { nav.add(Screen.Characters) },
                    onSearch = { nav.add(Screen.Search) },
                    charName = conversation.current?.charName ?: "",
                    charImage = conversation.characterImage,
                    // 只显示当前角色卡的聊天列表
                    sessions = conversation.sessions.filter { it.charFile == (conversation.current?.charFile ?: "") },
                    currentSessionId = conversation.current?.id,
                    onOpenSession = { id ->
                        scope.launch { drawerState.close() }
                        onAction(ChatAction.OpenSession(id))
                    },
                    onRenameSession = { id, title -> overlayState.renameSession = id to title },
                    onPinSession = { id, pinned -> onAction(ChatAction.SetSessionPinned(id, pinned)) },
                    onRegenerateTitle = { onAction(ChatAction.RegenerateTitle(it)) },
                    onDeleteSession = { id -> overlayState.deleteSessionId = id },
                    showChatListDate = prefs.showChatListDate,
                    longPressHaptic = prefs.longPressHaptic,
                    onUserProfileChanged = { name, description ->
                        onAction(ChatAction.UpdateUserProfile(name, description))
                    },
                )
                }
            },
            content = {
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    ChatTopBar(
                        title = chatBarTitle,
                        subtitle = chatBarSubtitle,
                        onDrawer = { scope.launch { drawerState.open() } },
                        onNewChat = { onAction(ChatAction.NewChat) },
                    )
                },
                bottomBar = {
                    ChatBottomArea(
                        state = inputState,
                        attachments = input.attachments,
                        onRemoveAttachment = { onAction(ChatAction.RemoveAttachment(it)) },
                        showAttachment = overlayState.showAttachment,
                        onToggleAttachment = { overlayState.showAttachment = !overlayState.showAttachment },
                        editing = input.editingTimestamp != null,
                        onCancelEdit = { onAction(ChatAction.CancelEdit) },
                        onExpand = {
                            val text = inputState.text.toString()
                            if (text.isNotBlank()) {
                                overlayState.copyPanel = CopyPanel(resources.getString(R.string.input_content), text, editable = true)
                            }
                        },
                        currentModelId = displayModelId,
                        reasoning = model.reasoning,
                        imageGenerationEnabled = model.imageGenerationAvailable,
                        generating = generation.running,
                        searchEnabled = search.enabled,
                        searchProvider = search.providerName,
                        builtInSearchEnabled = search.builtInEnabled,
                        onStop = { onAction(ChatAction.StopGeneration) },
                        onSend = {
                            onAction(ChatAction.SendMessage)
                            // 编辑是原地更新，不滚动到底部
                        },
                        onSelectModel = { modelSelector.open() },
                        onSelectReasoning = { overlayState.showReasoningPicker = true },
                        onOpenImageGenerationSettings = { overlayState.showImageGenerationSettings = true },
                        onOpenSearch = { overlayState.showSearch = true },
                        onCamera = media.takePhoto,
                        onGallery = media.pickImages,
                        onFile = media.pickFiles,
                        quickReplies = input.quickReplies,
                        onQuickReply = { qr ->
                            onAction(ChatAction.UseQuickReply(qr))
                        },
                        enterToSend = prefs.enterToSend,
                    )
                }
            ) { padding ->
                // 消息列表来自 Paging 3（DB 为准），已加载页叠加内存 overlay（流式内容 / 分支切换编辑
                // 的乐观即时反映）后供渲染与滚动锚定用。分页为空（未落盘的新会话，仅开场白）时回退到
                // 内存会话，保证开场白可见、首帧不闪空。
                val lazyMessages = pagedMessages.collectAsLazyPagingItems()
                val overlays = conversation.overlays
                val genTs = generation.targetTimestamp
                val usePaging = lazyMessages.itemCount > 0
                val inMemoryMessages = conversation.current?.messages.orEmpty()
                val inMemoryVisibleMessages = remember(inMemoryMessages) {
                    inMemoryMessages.filterNot(ChatMessage::isHidden)
                }
                val pagedBase = pagedMessageWindow(
                    paged = lazyMessages.itemSnapshotList.items,
                    inMemory = inMemoryVisibleMessages,
                )
                val pagedMessageIndexOffset = remember(
                    conversation.current?.totalMessageCount,
                    pagedBase.size,
                    inMemoryMessages.size,
                ) {
                    if (inMemoryMessages.isNotEmpty()) 0
                    else ((conversation.current?.totalMessageCount ?: pagedBase.size) - pagedBase.size)
                        .coerceAtLeast(0)
                }
                // 加载中视为已抵达底部：append 的 endOfPaginationReached 会在 refresh 时暂时重置，
                // 此时若隐藏新 overlay，刚完成的消息会闪掉一帧再回来。
                val append = lazyMessages.loadState.append
                val allowOverlayAppend = !usePaging || append.endOfPaginationReached ||
                    append is LoadState.Loading || lazyMessages.loadState.refresh is LoadState.Loading
                val msgs = remember(pagedBase, overlays, allowOverlayAppend) {
                    mergeMessageWindow(pagedBase, overlays, allowOverlayAppend)
                }
                val waitingForInitialMessagePage =
                    lazyMessages.itemCount == 0 && pagedBase.isEmpty() &&
                        ((conversation.current?.totalMessageCount ?: 0) > 0 ||
                            inMemoryVisibleMessages.size > ImmediateMessageFallbackLimit) &&
                        lazyMessages.loadState.refresh !is LoadState.Error
                // 前端卡只获得当前已加载的分页窗口。完整会话仍可通过 chat.get-messages RPC 按需读取，
                // 避免每个可见 WebView 都持有并解析整段聊天历史。
                val frontendMessages = msgs
                val messageIndexes = remember(
                    conversation.current?.id,
                    inMemoryMessages.size,
                    inMemoryMessages.firstOrNull()?.ts,
                    inMemoryMessages.lastOrNull()?.ts,
                    pagedBase,
                    pagedMessageIndexOffset,
                    conversation.current?.messageTimestamps,
                ) {
                    if (inMemoryMessages.isNotEmpty()) {
                        messageIndexesByTimestamp(inMemoryMessages)
                    } else {
                        messageIndexesForWindow(
                            messages = pagedBase,
                            allTimestamps = conversation.current?.messageTimestamps.orEmpty(),
                            fallbackOffset = pagedMessageIndexOffset,
                        )
                    }
                }
                val frontend = rememberChatFrontendBindings(state, frontendMessages, messageIndexes, onAction)
                // rememberUpdatedState：快照 Flow 的 collect lambda 里引用 msgs，需要始终读到最新值
                val msgsNow by rememberUpdatedState(msgs)
                // overlay 收敛：分页已把该 ts 的最终内容补齐、且该行不在生成中时撤下 overlay
                //（避免"异步写库→分页刷新"时间差造成的空帧/陈旧内容闪烁）
                val settledOverlayTs = settledOverlayTimestamps(
                    base = pagedBase,
                    overlays = overlays,
                    generating = generation.running,
                    generationTargetTs = genTs,
                )
                LaunchedEffect(settledOverlayTs) {
                    settledOverlayTs.forEach { onAction(ChatAction.ClearOverlay(it)) }
                }

                // ── 滚动状态机的外部输入 ──
                // 每次重组把状态机依赖的最新值刷进去（等价 rememberUpdatedState：其协程只读
                // "当前值"，不需要驱动重组）。scrollCtrl 本身已提升到全屏页面切换上方。
                val density = LocalDensity.current
                scrollCtrl.inputs.apply {
                    // 只有生成目标是末条消息时才自动跟随钉底；重答中间消息时原地生成，视口不动
                    generatingAtEnd = generation.running && genTs != null && genTs == msgs.lastOrNull()?.ts
                    hasMessages = msgs.isNotEmpty()
                    messageCount = msgs.size
                    bottomSlackPx = with(density) { 80.dp.toPx() }
                    touchBottomSlackPx = with(density) { 24.dp.toPx() }
                }
                scrollCtrl.navButtonsMode = prefs.navButtonsMode
                // 手势订阅 + 流式跟随两个长循环：随聊天区域在屏/离屏启停，
                // 不能提升到全屏页面切换之上（否则会对着已离开组合的列表操作）
                LaunchedEffect(scrollCtrl) { scrollCtrl.runLoops() }

                // 用户发送、重试、删除末条：回到底部并恢复跟随。
                // 用 snapshotFlow.drop(1) 跳过当前值，避免从全屏页面返回时（LaunchedEffect 重入）误钉底。
                LaunchedEffect(Unit) {
                    snapshotFlow { scrollToBottomTrigger }
                        .drop(1)
                        .collect { scrollCtrl.onSendOrReset() }
                }
                // 打开/切换会话：回到底部并重置。用 lastPinnedSessionId 跳过"从全屏页面返回后重入"的情况。
                LaunchedEffect(conversation.current?.id) {
                    val sessionId = conversation.current?.id
                    if (sessionId != null && sessionId != lastPinnedSessionId) {
                        lastPinnedSessionId = sessionId
                        scrollCtrl.onSessionOpened()
                    }
                }
                // The session may be selected before Paging publishes its first page. Re-apply the
                // pending bottom anchor as soon as items arrive, before normal user scrolling begins.
                LaunchedEffect(conversation.current?.id, lazyMessages.itemCount) {
                    val sessionId = conversation.current?.id
                    if (sessionId != null && lazyMessages.itemCount > 0 &&
                        firstPagePinnedSessionId != sessionId) {
                        firstPagePinnedSessionId = sessionId
                        scrollCtrl.pinToBottom()
                    }
                }
                // 生成结束：仍在跟随且生成目标是末条消息则钉住底部（正文切 Markdown、工具栏出现会改高度）。
                // 用 snapshotFlow.drop(1) 跳过当前值，避免从全屏页面返回时（正在生成中才离开的罕见场景）误钉底。
                var lastObservedGenerating by remember { mutableStateOf(generation.running) }
                LaunchedEffect(generation.running, generation.targetTimestamp) {
                    if (lastObservedGenerating && !generation.running &&
                        generation.targetTimestamp == msgsNow.lastOrNull()?.ts) {
                        scrollCtrl.onGenerationFinished()
                    }
                    lastObservedGenerating = generation.running
                }
                // 键盘弹出只在贴底时跟随上移；在上方读历史时视口保持不动。
                // 跟随动作走状态机的统一入口，与流式跟随共用同一套手势让位判断。
                ImeLazyListAutoScroller(
                    lazyListState = scrollCtrl.listState,
                    shouldFollow = scrollCtrl::isAtBottom,
                    onFollow = scrollCtrl::snapToBottom,
                )

                Box(Modifier.fillMaxSize()) {
                    if (msgs.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            if (waitingForInitialMessagePage) {
                                CircularProgressIndicator()
                            } else {
                                Text(stringResource(R.string.chat_empty_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(
                            // 重要：只把 top padding 放在 modifier 上避免约束变化导致滚动位置重置。
                            // bottom padding 通过 contentPadding 处理。
                            modifier = Modifier
                                .testTag("chat_messages")
                                .fillMaxSize()
                                .padding(top = padding.calculateTopPadding())
                                .padding(horizontal = Space16),
                            state = scrollCtrl.listState,
                            contentPadding = PaddingValues(
                                top = Space8,
                                // spacedBy(16) 在末条消息与底部锚点之间固定垫了 16dp，无法对单个
                                // item 豁免；这里反向扣掉，让贴底内容真正贴近输入栏。被扣的 16dp
                                // 落在不透明底栏背后，不可见
                                bottom = (padding.calculateBottomPadding() - Space16).coerceAtLeast(0.dp)
                            ),
                            verticalArrangement = Arrangement.spacedBy(Space16)
                        ) {
                            itemsIndexed(msgs, key = { _, msg -> msg.ts }) { i, msg ->
                                // 触发分页按需加载：访问对应下标即向 Paging 登记位置，滚到边缘时预取相邻页。
                                // msgs 与已加载分页共享下标（enablePlaceholders=false，itemCount 即已加载数）。
                                if (usePaging && i < lazyMessages.itemCount) lazyMessages[i]
                                if (msg.role == "user") {
                                    UserMsg(
                                        name = profile.userName,
                                        text = msg.content,
                                        images = msg.images,
                                        files = msg.files,
                                        onCopy = { media.copyText(msg.content) },
                                        onRegenerate = {
                                            // 其后紧跟的 AI 回复在中间时原地重答，不滚到底部
                                            overlayState.confirmRegenerate(prefs.confirmRegenerate) {
                                                val midRegen = i + 1 < msgs.lastIndex && msgs[i + 1].role == "assistant"
                                                onAction(
                                                    ChatAction.RegenerateAfterUser(
                                                        timestamp = msg.ts,
                                                        scrollToBottom = !midRegen,
                                                    ),
                                                )
                                            }
                                        },
                                        onMore = {
                                            overlayState.menuTarget = MessageMenuTarget(msg, msgs, i == msgs.lastIndex)
                                        },
                                        scale = fontScale,
                                        avatarBitmap = profile.userAvatar,
                                        showAvatar = prefs.showUserAvatar,
                                        showName = prefs.showUserName,
                                        showTimestamp = prefs.showUserTimestamp,
                                        showActions = prefs.showUserActions,
                                        timestamp = msg.ts,
                                        renderPrefs = renderPrefs.copy(markdown = prefs.userMarkdown),
                                    )
                                } else {
                                    // 切分支后的落点锚定见 ChatScrollController.switchAnchored。
                                    // 这里只做可切换性预判，避免边界处无谓的重锚定。
                                    fun switchAltAnchored(dir: Int) {
                                        if (msg.alts.size < 2 || (msg.altIdx + dir) !in 0..msg.alts.lastIndex) return
                                        scrollCtrl.switchAnchored(index = i, isLast = i == msgs.lastIndex) {
                                            onAction(ChatAction.SwitchAlternative(msg, dir))
                                        }
                                    }
                                    AIMsg(
                                        msg = msg,
                                        isStreaming = generation.running && msg.ts == genTs,
                                        onCopy = { media.copyText(msg.content) },
                                        onRegenerate = {
                                            overlayState.confirmRegenerate(prefs.confirmRegenerate) {
                                                onAction(
                                                    ChatAction.RegenerateAssistant(
                                                        timestamp = msg.ts,
                                                        scrollToBottom = i == msgs.lastIndex,
                                                    ),
                                                )
                                            }
                                        },
                                        onMore = {
                                            overlayState.menuTarget = MessageMenuTarget(msg, msgs, i == msgs.lastIndex)
                                        },
                                        onPrevAlt = { switchAltAnchored(-1) },
                                        onNextAlt = { switchAltAnchored(+1) },
                                        onSpeak = { onAction(ChatAction.SpeakMessage(msg)) },
                                        speaking = profile.speakingTimestamp == msg.ts,
                                        scale = fontScale,
                                        regexScripts = conversation.displayRegexScripts,
                                        depth = msgs.size - 1 - i,
                                        userName = profile.userName,
                                        charName = conversation.card?.name ?: conversation.current?.charName ?: "",
                                        showModelIcon = prefs.showModelIcon,
                                        showModelName = prefs.showModelName,
                                        showModelTimestamp = prefs.showModelTimestamp,
                                        showTokenUsage = prefs.showTokenUsage,
                                        showTokenSpeed = prefs.showTokenSpeed,
                                        showGenerationTime = prefs.showGenerationTime,
                                        autoCollapseThinking = prefs.autoCollapseThinking,
                                        thinkingMarkdown = prefs.thinkingMarkdown,
                                        renderPrefs = renderPrefs,
                                        chatMessagesJson = frontend.messagesJson,
                                        frontendContextJson = frontend.contextJson(msg),
                                        localVariablesJson = frontend.localVariablesJson,
                                        globalVariablesJson = frontend.globalVariablesJson,
                                        onSetInputText = { onAction(ChatAction.SetInputText(it)) },
                                        onSetChatMessage = frontend::updateMessage,
                                        onSelectChatMessageSwipe = frontend::selectAlternative,
                                        onReplaceVariables = { scopeName, values ->
                                            onAction(ChatAction.ReplaceFrontendVariables(scopeName, values))
                                        },
                                        rpcCall = frontendRpc,
                                    )
                                }
                            }
                            // 末尾锚点：让“越界索引 requestScrollToItem”clamp 后停在这条 1dp 行，
                            // 从而稳定钉住真正的底部，而不是把最后一条高消息的顶部对齐到视口顶。
                            item(key = "bottom_anchor") { Spacer(Modifier.height(1.dp)) }
                        }
                    }

                    // ── 悬浮滚动导航按钮栏 ──
                    // msgs 为空时 LazyColumn 不在组合中，listState.layoutInfo 停留在上个会话的
                    // 旧值，contentOverflows 会误报——新建空会话（默认角色无开场白）不显示
                    // 可见性按偏好模式：始终显示 / 滚动时显示（出现后静止两秒隐藏）/ 永不显示
                    val navBtnVisible = when (prefs.navButtonsMode) {
                        NavButtonsMode.ALWAYS -> msgs.isNotEmpty()
                        NavButtonsMode.ON_SCROLL -> msgs.isNotEmpty() && scrollCtrl.showNavButtons
                        NavButtonsMode.NEVER -> false
                    }
                    ScrollNavButtons(
                        visible = navBtnVisible,
                        onScrollToTop = scrollCtrl::scrollToTop,
                        onPreviousMessage = { scrollCtrl.jumpToAdjacentMessage(forward = false) },
                        onNextMessage = { scrollCtrl.jumpToAdjacentMessage(forward = true) },
                        onScrollToBottom = scrollCtrl::scrollToBottom,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            // Scaffold 的 content 铺满全屏、底栏叠在上层：必须加上底栏高度才不会被盖住
                            .padding(end = Space8, bottom = Space16 + padding.calculateBottomPadding()),
                    )

                }
            }
            }
        )
    }

    ChatMessageOverlays(
        overlays = overlayState,
        state = state,
        inputState = inputState,
        media = media,
        onAction = onAction,
        onScrollToBottom = { scrollToBottomTrigger++ },
    )

    ChatPickerOverlays(
        state = state,
        modelSelector = modelSelector,
        displayProvider = displayProv,
        displayModelId = displayModelId,
        showReasoning = overlayState.showReasoningPicker,
        showImageGeneration = overlayState.showImageGenerationSettings,
        showCharacter = overlayState.showCharPicker,
        showSearch = overlayState.showSearch,
        onDismissReasoning = { overlayState.showReasoningPicker = false },
        onDismissImageGeneration = { overlayState.showImageGenerationSettings = false },
        onDismissCharacter = { overlayState.showCharPicker = false },
        onDismissSearch = { overlayState.showSearch = false },
        onOpenSearchConfig = {
            overlayState.showSearch = false
            nav.add(Screen.WebSearch)
        },
        onAction = onAction,
    )
}
