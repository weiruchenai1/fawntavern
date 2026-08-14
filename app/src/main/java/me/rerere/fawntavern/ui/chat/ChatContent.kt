package me.rerere.fawntavern.ui.chat

import android.content.ClipData
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import me.rerere.fawntavern.ui.components.FloatingWindow
import me.rerere.fawntavern.ui.components.rememberInteractiveDrawerState
import me.rerere.fawntavern.ui.components.InteractiveDrawer
import me.rerere.fawntavern.ui.components.ModelSelectorSheet
import me.rerere.fawntavern.ui.components.RenameDialog
import me.rerere.fawntavern.ui.components.rememberModelSelectorState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.settings.NavButtonsMode
import me.rerere.fawntavern.data.settings.ThemeMode
import me.rerere.fawntavern.domain.GenerationActionGuard
import me.rerere.fawntavern.ui.hooks.ImeLazyListAutoScroller
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.components.vibrate

private typealias Screen = ChatDestination

@Composable
internal fun ChatContent(
    vm: ChatViewModel,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    solidBackground: Boolean = false,
    onSolidBackgroundChange: (Boolean) -> Unit = {},
    startAtSettings: Boolean = false,
) {
    val drawerState = rememberInteractiveDrawerState()
    val scope = rememberCoroutineScope()
    // ── 页面返回栈（纯 UI 状态） ──
    val nav = rememberChatNavigationStack(startAtSettings)
    fun navBack() { nav.removeLastOrNull() }
    // ── 弹层标志 ──
    var showAttachment by rememberSaveable { mutableStateOf(false) }
    val modelSelector = rememberModelSelectorState(vm.displayModelSpec() ?: "", vm.apiConfig.providers)
    var showReasoningPicker by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showCharPicker by rememberSaveable { mutableStateOf(false) }
    var cameraImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    // ── 消息操作弹窗状态（按消息 ts 定位，与分页/内存窗口无关） ──
    var menuTargetIdx by rememberSaveable { mutableStateOf<Long?>(null) }
    /** 全屏底部面板内容（消息全文/输入框全文共用），非 null 时显示 */
    var copyPanel by remember { mutableStateOf<CopyPanel?>(null) }
    var deleteSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameSession by remember { mutableStateOf<Pair<String, String>?>(null) }
    var scrollToBottomTrigger by remember { mutableIntStateOf(0) }
    // 重新生成前确认：偏好开启时把待执行的重答存这里，弹框确认后执行
    var pendingRegenerate by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 删除前确认：偏好开启时把待执行的删除存这里，弹框确认后执行
    var pendingDeleteCurrentVersion by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingDeleteAllVersions by remember { mutableStateOf<(() -> Unit)?>(null) }

    val ctx = LocalContext.current
    val mediaInput = remember(ctx) { ChatMediaInput(ctx) }
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun copyText(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", text)))
        }
        Toast.makeText(ctx, resources.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    // 发送/重答的统一善后：成功则钉到底部（重答中间消息时不滚动，原地生成）；
    // 未选模型则提示并打开模型选择面板
    fun handleOutcome(outcome: ChatViewModel.SendOutcome, scroll: Boolean = true) {
        when (outcome) {
            ChatViewModel.SendOutcome.STARTED -> if (scroll) scrollToBottomTrigger++
            ChatViewModel.SendOutcome.NO_MODEL -> {
                Toast.makeText(ctx, resources.getString(R.string.select_model_first), Toast.LENGTH_SHORT).show()
                modelSelector.open()
            }
            ChatViewModel.SendOutcome.SKIPPED -> {}
            ChatViewModel.SendOutcome.FILE_TOO_LARGE ->
                Toast.makeText(ctx, resources.getString(R.string.file_too_large_to_send), Toast.LENGTH_SHORT).show()
        }
    }
    // 附件落盘失败（提供方不报大小等兜底场景）：附件与输入已恢复，这里弹提示
    LaunchedEffect(vm.sendError) {
        vm.sendError?.let { err ->
            Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show()
            vm.consumeSendError()
        }
    }
    LaunchedEffect(vm.promptContextFailures) {
        val failures = vm.promptContextFailures
        if (failures.isNotEmpty()) {
            val names = failures.map { it.name }.distinct().joinToString()
            Toast.makeText(
                ctx,
                resources.getString(R.string.prompt_context_load_failed_fmt, names),
                Toast.LENGTH_LONG,
            ).show()
            vm.consumePromptContextFailures()
        }
    }

    // ── 附件选取 launcher（相册/文件均可多选；文件里选到图片按 MIME 归为图片）──
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri -> vm.attachments = vm.attachments + Attachment(uri, isImage = true) }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri -> vm.attachments = vm.attachments + Attachment(uri, isImage = mediaInput.isImage(uri)) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraImageUri?.let(Uri::parse)?.let { uri ->
            if (ok) vm.attachments = vm.attachments + Attachment(uri, isImage = true)
            else mediaInput.discardCameraFile(uri)
        }
        cameraImageUri = null
    }

    // 抽屉里可能改了用户名/头像，关抽屉时刷新
    LaunchedEffect(drawerState.isOpen) {
        if (!drawerState.isOpen) vm.reloadUserProfile()
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

    // ── TTS 悬浮窗：挂在 App 窗口之上，朗读时悬浮于屏幕任意页面；初始位置在顶栏下方左缘 ──
    val density = LocalDensity.current
    val ttsBarOffset = with(density) {
        Offset(Space16.toPx(), WindowInsets.statusBars.getTop(this).toFloat() + 48.dp.toPx())
    }
    FloatingWindow(
        tag = "tts_controller",
        themeMode = themeMode,
        visibility = vm.ttsUi.speaking,
        initialOffsetX = ttsBarOffset.x,
        initialOffsetY = ttsBarOffset.y,
    ) {
        TtsFloatingBar(
            state = vm.ttsUi,
            onPause = vm::pauseTts,
            onResume = vm::resumeTts,
            onStop = { vm.stopSpeaking() },
            onFastForward = vm::fastForwardTts,
            onCycleSpeed = vm::cycleTtsSpeed,
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
            viewModel = vm,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            solidBackground = solidBackground,
            onSolidBackgroundChange = onSolidBackgroundChange,
            onBack = ::navBack,
            onNavigate = nav::add,
            onOpenSearchSession = { id ->
                vm.openSession(id)
                drawerState.snapClose()
                navBack()
            },
        )
        return
    }

    vm.modelRevision // 读 state 建依赖：selectModel 后重读 displayModelSpec，刷新选中图标
    val displaySpec = vm.displayModelSpec()
    val displayProvId = displaySpec?.substringBefore("::") ?: ""
    val displayModelId = displaySpec?.substringAfter("::", "") ?: ""
    val displayProv = if (displaySpec != null) vm.apiConfig.providers.find { it.id == displayProvId && it.enabled } else null

    // 偏好和字号由 ViewModel 提供；设置页返回时统一刷新快照。
    val prefs = vm.uiSettings
    val fontScale = prefs.fontScale
    val renderPrefs = RenderPrefs(
        markdown = prefs.characterMarkdown,
        math = prefs.mathRendering,
        autoCollapseCode = prefs.autoCollapseCode,
        codeCollapseLines = prefs.codeCollapseLines,
    )
    // 重新生成前弹出确认：偏好开启时先弹确认框，确认后再执行真正的重答
    fun maybeRegenerate(action: () -> Unit) {
        if (prefs.confirmRegenerate) pendingRegenerate = action else action()
    }
    // 删除当前版本前弹出确认：偏好开启时先弹确认框
    fun maybeDeleteCurrentVersion(action: () -> Unit) {
        if (prefs.confirmDeleteCurrentVersion) pendingDeleteCurrentVersion = action else action()
    }
    // 删除全部版本前弹出确认：偏好开启时先弹确认框
    fun maybeDeleteAllVersions(action: () -> Unit) {
        if (prefs.confirmDeleteAllVersions) pendingDeleteAllVersions = action else action()
    }
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
                    onCharSelect = { showCharPicker = true },
                    onCharList = { nav.add(Screen.Characters) },
                    onSearch = { nav.add(Screen.Search) },
                    charName = vm.session?.charName ?: "",
                    charImage = vm.charImageBitmap,
                    // 只显示当前角色卡的聊天列表
                    sessions = vm.sessions.filter { it.charFile == (vm.session?.charFile ?: "") },
                    currentSessionId = vm.session?.id,
                    onOpenSession = { id ->
                        scope.launch { drawerState.close() }
                        vm.openSession(id)
                    },
                    onRenameSession = { id, title -> renameSession = id to title },
                    onPinSession = vm::setSessionPinned,
                    onRegenerateTitle = vm::regenerateTitle,
                    onDeleteSession = { id -> deleteSessionId = id },
                    showChatListDate = prefs.showChatListDate,
                    longPressHaptic = prefs.longPressHaptic,
                    onUserProfileChanged = vm::updateUserProfile,
                )
                }
            },
            content = {
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    ChatTopBar(
                        title = (vm.session?.charName ?: "").ifBlank { stringResource(R.string.default_character) },
                        subtitle = if (displaySpec == null) stringResource(R.string.no_model_selected)
                                   else "${displayProv?.models?.find { it.id == displayModelId }?.name ?: displayModelId} (${displayProv?.name ?: ""})",
                        onDrawer = { scope.launch { drawerState.open() } },
                        onNewChat = { vm.newChat() },
                    )
                },
                bottomBar = {
                    ChatBottomArea(
                        state = vm.inputState,
                        attachments = vm.attachments,
                        onRemoveAttachment = { vm.attachments = vm.attachments - it },
                        showAttachment = showAttachment,
                        onToggleAttachment = { showAttachment = !showAttachment },
                        editing = vm.editingTs != null,
                        onCancelEdit = { vm.cancelEdit() },
                        onExpand = {
                            if (vm.inputText.isNotBlank()) {
                                copyPanel = CopyPanel(resources.getString(R.string.input_content), vm.inputText, editable = true)
                            }
                        },
                        currentModelId = displayModelId,
                        reasoning = vm.reasoning,
                        generating = vm.generating,
                        searchEnabled = vm.searchEnabled,
                        searchProvider = vm.searchProviderName,
                        builtInSearchEnabled = vm.builtInSearchEnabled,
                        onStop = { vm.stopGenerate() },
                        onSend = {
                            val wasEditing = vm.editingTs != null
                            val outcome = vm.sendMessage()
                            if (outcome != ChatViewModel.SendOutcome.SKIPPED) keyboardController?.hide()
                            // 编辑是原地更新，不滚动到底部
                            handleOutcome(outcome, scroll = !wasEditing)
                        },
                        onSelectModel = { modelSelector.open() },
                        onSelectReasoning = { showReasoningPicker = true },
                        onOpenSearch = { showSearch = true },
                        onCamera = {
                            val uri = mediaInput.createCameraUri()
                            cameraImageUri = uri.toString()
                            cameraLauncher.launch(uri)
                        },
                        onGallery = { galleryLauncher.launch("image/*") },
                        onFile = { fileLauncher.launch("*/*") },
                        quickReplies = vm.quickReplies,
                        onQuickReply = { qr ->
                            val outcome = vm.onQuickReply(qr)
                            if (outcome != ChatViewModel.SendOutcome.SKIPPED) keyboardController?.hide()
                            handleOutcome(outcome)
                        },
                        enterToSend = prefs.enterToSend,
                    )
                }
            ) { padding ->
                // 消息列表来自 Paging 3（DB 为准），已加载页叠加内存 overlay（流式内容 / 分支切换编辑
                // 的乐观即时反映）后供渲染与滚动锚定用。分页为空（未落盘的新会话，仅开场白）时回退到
                // 内存会话，保证开场白可见、首帧不闪空。
                val lazyMessages = vm.pagedMessages.collectAsLazyPagingItems()
                val overlays = vm.overlays
                val genTs = vm.genTargetTs
                val usePaging = lazyMessages.itemCount > 0
                val pagedBase: List<ChatMessage> = lazyMessages.itemSnapshotList.items
                    .ifEmpty { vm.session?.messages ?: emptyList() }
                // 加载中视为已抵达底部：append 的 endOfPaginationReached 会在 refresh 时暂时重置，
                // 此时若隐藏新 overlay，刚完成的消息会闪掉一帧再回来。
                val append = lazyMessages.loadState.append
                val allowOverlayAppend = !usePaging || append.endOfPaginationReached ||
                    append is LoadState.Loading || lazyMessages.loadState.refresh is LoadState.Loading
                val msgs = mergeMessageWindow(pagedBase, overlays, allowOverlayAppend)
                // rememberUpdatedState：快照 Flow 的 collect lambda 里引用 msgs，需要始终读到最新值
                val msgsNow by rememberUpdatedState(msgs)
                // overlay 收敛：分页已把该 ts 的最终内容补齐、且该行不在生成中时撤下 overlay
                //（避免"异步写库→分页刷新"时间差造成的空帧/陈旧内容闪烁）
                val settledOverlayTs = settledOverlayTimestamps(
                    base = pagedBase,
                    overlays = overlays,
                    generating = vm.generating,
                    generationTargetTs = genTs,
                )
                LaunchedEffect(settledOverlayTs) { settledOverlayTs.forEach { vm.clearOverlay(it) } }

                // ── 滚动状态机的外部输入 ──
                // 每次重组把状态机依赖的最新值刷进去（等价 rememberUpdatedState：其协程只读
                // "当前值"，不需要驱动重组）。scrollCtrl 本身已提升到全屏页面切换上方。
                val density = LocalDensity.current
                scrollCtrl.inputs.apply {
                    // 只有生成目标是末条消息时才自动跟随钉底；重答中间消息时原地生成，视口不动
                    generatingAtEnd = vm.generating && genTs != null && genTs == msgs.lastOrNull()?.ts
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
                LaunchedEffect(vm.session?.id) {
                    if (vm.session?.id != null && vm.session?.id != lastPinnedSessionId) {
                        lastPinnedSessionId = vm.session?.id
                        scrollCtrl.onSessionOpened()
                    }
                }
                // 生成结束：仍在跟随且生成目标是末条消息则钉住底部（正文切 Markdown、工具栏出现会改高度）。
                // 用 snapshotFlow.drop(1) 跳过当前值，避免从全屏页面返回时（正在生成中才离开的罕见场景）误钉底。
                LaunchedEffect(Unit) {
                    snapshotFlow { vm.generating }
                        .drop(1)
                        .collect { generating ->
                            if (!generating && vm.genTargetTs != null &&
                                vm.genTargetTs == msgsNow.lastOrNull()?.ts) {
                                scrollCtrl.onGenerationFinished()
                            }
                        }
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
                            Text(stringResource(R.string.chat_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            // 重要：只把 top padding 放在 modifier 上避免约束变化导致滚动位置重置。
                            // bottom padding 通过 contentPadding 处理。
                            modifier = Modifier
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
                                        name = vm.userName,
                                        text = msg.content,
                                        images = msg.images,
                                        files = msg.files,
                                        onCopy = { copyText(msg.content) },
                                        onRegenerate = {
                                            // 其后紧跟的 AI 回复在中间时原地重答，不滚到底部
                                            maybeRegenerate {
                                                val midRegen = i + 1 < msgs.lastIndex && msgs[i + 1].role == "assistant"
                                                handleOutcome(vm.regenerateAfterUser(msg.ts), scroll = !midRegen)
                                            }
                                        },
                                        onMore = { menuTargetIdx = msg.ts },
                                        scale = fontScale,
                                        avatarBitmap = vm.userAvatarBitmap,
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
                                            vm.switchAlt(msg.ts, dir)
                                        }
                                    }
                                    AIMsg(
                                        msg = msg,
                                        isStreaming = vm.generating && msg.ts == genTs,
                                        onCopy = { copyText(msg.content) },
                                        onRegenerate = {
                                            maybeRegenerate {
                                                handleOutcome(vm.regenerateAi(msg.ts), scroll = i == msgs.lastIndex)
                                            }
                                        },
                                        onMore = { menuTargetIdx = msg.ts },
                                        onPrevAlt = { switchAltAnchored(-1) },
                                        onNextAlt = { switchAltAnchored(+1) },
                                        onSpeak = { vm.speakMessage(msg.ts) },
                                        speaking = vm.speakingTs == msg.ts,
                                        scale = fontScale,
                                        regexScripts = vm.displayRegexScripts,
                                        depth = msgs.size - 1 - i,
                                        userName = vm.userName,
                                        charName = vm.currentCard?.name ?: vm.session?.charName ?: "",
                                        showModelIcon = prefs.showModelIcon,
                                        showModelName = prefs.showModelName,
                                        showModelTimestamp = prefs.showModelTimestamp,
                                        showTokenUsage = prefs.showTokenUsage,
                                        showTokenSpeed = prefs.showTokenSpeed,
                                        showGenerationTime = prefs.showGenerationTime,
                                        autoCollapseThinking = prefs.autoCollapseThinking,
                                        thinkingMarkdown = prefs.thinkingMarkdown,
                                        renderPrefs = renderPrefs,
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

    // ── 消息操作菜单 ──
    val menuTs = menuTargetIdx
    // 与渲染一致地取目标：overlay（流式/乐观态）优先，回退到内存会话
    val menuMsg = menuTs?.let { ts -> vm.overlays[ts] ?: vm.session?.messages?.firstOrNull { it.ts == ts } }
    if (menuTs != null && menuMsg != null) {
        MessageMenu(
            onDismiss = { menuTargetIdx = null },
            onSelectCopy = {
                copyPanel = CopyPanel(resources.getString(R.string.select_copy), menuMsg.content)
                menuTargetIdx = null
            },
            onEdit = {
                vm.startEdit(menuTs)
                menuTargetIdx = null
            },
            onDeleteCurrentVersion = {
                val ts = menuTs
                maybeDeleteCurrentVersion {
                    val wasLast = menuMsg.ts == vm.session?.messages?.lastOrNull()?.ts
                    vm.deleteMessage(ts)
                    if (wasLast) scrollToBottomTrigger++
                }
                menuTargetIdx = null
            },
            onDeleteAllVersions = {
                val ts = menuTs
                maybeDeleteAllVersions {
                    val wasLast = menuMsg.ts == vm.session?.messages?.lastOrNull()?.ts
                    vm.deleteAllVersions(ts)
                    if (wasLast) scrollToBottomTrigger++
                }
                menuTargetIdx = null
            },
            hasMultipleVersions = menuMsg.alts.size > 1,
        )
    }

    // ── 全屏底部面板：消息全文（只读）/ 输入框全文（可编辑，与输入框共用同一 TextFieldState）──
    copyPanel?.let { panel ->
        TextCopySheet(
            title = panel.title,
            text = if (panel.editable) vm.inputText else panel.text,
            onCopyAll = {
                copyText(if (panel.editable) vm.inputText else panel.text)
                copyPanel = null
            },
            onDismiss = { copyPanel = null },
            editState = if (panel.editable) vm.inputState else null,
        )
    }

    renameSession?.let { (id, title) ->
        RenameDialog(
            initialName = title,
            label = stringResource(R.string.chat_title_label),
            onConfirm = { newTitle ->
                if (newTitle.isNotBlank()) {
                    vm.renameSession(id, newTitle)
                    renameSession = null
                }
            },
            onDismiss = { renameSession = null },
        )
    }

    ChatConfirmationDialogs(
        showDeleteSession = deleteSessionId != null,
        deleteSessionEnabled = GenerationActionGuard.allowsMutation(vm.generating),
        onDeleteSession = {
            deleteSessionId?.let(vm::deleteSession)
            deleteSessionId = null
        },
        onDismissDeleteSession = { deleteSessionId = null },
        showRegenerate = pendingRegenerate != null,
        onRegenerate = {
            val action = pendingRegenerate
            pendingRegenerate = null
            action?.invoke()
        },
        onDismissRegenerate = { pendingRegenerate = null },
        showDeleteCurrentVersion = pendingDeleteCurrentVersion != null,
        onDeleteCurrentVersion = {
            val action = pendingDeleteCurrentVersion
            pendingDeleteCurrentVersion = null
            action?.invoke()
        },
        onDismissDeleteCurrentVersion = { pendingDeleteCurrentVersion = null },
        showDeleteAllVersions = pendingDeleteAllVersions != null,
        onDeleteAllVersions = {
            val action = pendingDeleteAllVersions
            pendingDeleteAllVersions = null
            action?.invoke()
        },
        onDismissDeleteAllVersions = { pendingDeleteAllVersions = null },
    )

    // ── 模型选择面板 ──
    ModelSelectorSheet(
        state = modelSelector,
        onSelect = { providerId, modelId ->
            vm.selectModel(providerId, modelId)
        },
    )

    // ── 思考预算面板 ──
    if (showReasoningPicker) {
        ReasoningPickerSheet(
            current = vm.reasoning,
            onSelect = {
                vm.updateReasoning(it)
                showReasoningPicker = false
            },
            onDismiss = { showReasoningPicker = false },
        )
    }

    // ── 角色选择面板 ──
    if (showCharPicker) {
        CharacterPickerSheet(
            currentFileName = vm.session?.charFile ?: "",
            onSelect = { fileName, displayName ->
                vm.openCharacter(fileName, displayName)
                showCharPicker = false
            },
            onDismiss = { showCharPicker = false },
        )
    }

    // ── 联网搜索面板 ──
    if (showSearch) {
        val searchServices = vm.searchServices
        SearchPickerSheet(
            searchEnabled = vm.searchEnabled,
            builtInSearchAvailable = vm.builtInSearchAvailable,
            builtInSearchEnabled = vm.builtInSearchEnabled,
            services = searchServices,
            // 用 VM 的响应式下标做高亮；配置页删过提供商后可能越界，收敛回有效范围
            selectedIndex = vm.searchProviderIndex.coerceIn(0, searchServices.lastIndex.coerceAtLeast(0)),
            onToggleSearch = { vm.toggleSearch() },
            onToggleBuiltInSearch = { vm.toggleBuiltInSearch() },
            onSelectProvider = { vm.selectSearchProvider(it) },
            onOpenConfig = {
                showSearch = false
                nav.add(Screen.WebSearch)
            },
            onDismiss = { showSearch = false },
        )
    }
}

/** 全屏底部面板的内容（消息全文 / 输入框全文共用同一面板）；editable = 输入框展开，正文可直接编辑 */
private data class CopyPanel(val title: String, val text: String, val editable: Boolean = false)

/** 状态栏高度（px），用于把悬浮窗初始位置放到顶栏之下 */
