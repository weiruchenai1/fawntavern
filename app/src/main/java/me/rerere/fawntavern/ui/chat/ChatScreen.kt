package me.rerere.fawntavern.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import java.io.File
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.settings.FontSizeStore
import me.rerere.fawntavern.data.settings.ThemeMode
import me.rerere.fawntavern.ui.api.ApiConfigScreen
import me.rerere.fawntavern.ui.character.CharacterListScreen
import me.rerere.fawntavern.ui.preset.PresetListScreen
import me.rerere.fawntavern.ui.settings.DataManagementScreen
import me.rerere.fawntavern.ui.settings.FontSizeScreen
import me.rerere.fawntavern.ui.settings.PromptLogScreen
import me.rerere.fawntavern.ui.settings.AboutScreen
import me.rerere.fawntavern.ui.settings.SettingsScreen
import me.rerere.fawntavern.ui.extension.ExtensionsScreen
import me.rerere.fawntavern.ui.hooks.ImeLazyListAutoScroller
import me.rerere.fawntavern.ui.worldbook.WorldBookListScreen
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space16

/** 聊天之上的全屏页面，以返回栈方式叠放（栈顶显示，返回键弹出） */
private enum class Screen {
    Settings, Presets, Characters, WorldBooks, ApiConfig, DataMgmt, FontSize, PromptLog, Search, Extensions, About,
}

@Composable
fun ChatScreen(themeMode: ThemeMode = ThemeMode.SYSTEM, onThemeModeChange: (ThemeMode) -> Unit = {}, startAtSettings: Boolean = false) {
    val vm: ChatViewModel = viewModel()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // ── 页面返回栈（纯 UI 状态） ──
    val nav = remember {
        mutableStateListOf<Screen>().apply { if (startAtSettings) add(Screen.Settings) }
    }
    fun navBack() { nav.removeLastOrNull() }
    // ── 弹层标志 ──
    var showAttachment by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showCharPicker by remember { mutableStateOf(false) }
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    // ── 消息操作弹窗状态（按消息 ts 定位，与分页/内存窗口无关） ──
    var menuTargetIdx by remember { mutableStateOf<Long?>(null) }
    var editTargetIdx by remember { mutableStateOf<Long?>(null) }
    var selectCopyText by remember { mutableStateOf<String?>(null) }
    var deleteSessionId by remember { mutableStateOf<String?>(null) }
    var scrollToBottomTrigger by remember { mutableStateOf(0) }

    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun copyText(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(ctx, ctx.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    // 发送/重答的统一善后：成功则钉到底部（重答中间消息时不滚动，原地生成）；
    // 未选模型则提示并打开模型选择面板
    fun handleOutcome(outcome: ChatViewModel.SendOutcome, scroll: Boolean = true) {
        when (outcome) {
            ChatViewModel.SendOutcome.STARTED -> if (scroll) scrollToBottomTrigger++
            ChatViewModel.SendOutcome.NO_MODEL -> {
                Toast.makeText(ctx, ctx.getString(R.string.select_model_first), Toast.LENGTH_SHORT).show()
                showModelPicker = true
            }
            ChatViewModel.SendOutcome.SKIPPED -> {}
        }
    }

    // ── 附件选取 launcher ──
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.attachments = vm.attachments + Attachment(it, isImage = true) }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.attachments = vm.attachments + Attachment(it, isImage = false) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraImageUri?.let { vm.attachments = vm.attachments + Attachment(it, isImage = true) }
    }

    // 抽屉里可能改了用户名/头像，关抽屉时刷新
    LaunchedEffect(drawerState.isOpen) {
        if (!drawerState.isOpen) vm.reloadUserProfile()
    }
    // 打开抽屉（按钮或边缘手势）即收起键盘
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.targetValue }.collect {
            if (it == DrawerValue.Open) keyboardController?.hide()
        }
    }

    // ── 滚动状态提升到全屏页面切换上方 ──
    // 全屏页面（设置/角色列表等）通过 when 分支 + return 实现，命中时整个聊天区域离开组合，
    // 其内所有 remember 状态被销毁。返回时从零重建 → 滚动位置丢失 + LaunchedEffect 误触钉底。
    // 把滚动状态机提升到 when 上方，使其存活在 ChatScreen 作用域内，不受 when 分支切换影响。
    val scrollCtrl = rememberChatScrollController()
    // 记录上次因为"开/切会话"钉底的 session id，切换全屏页返回不触发重钉
    var lastPinnedSessionId by remember { mutableStateOf<String?>(null) }

    // ── 全屏页面：渲染栈顶 ──
    // SaveableStateProvider 包裹每个分支：从 Settings 进入 Characters 再返回时，
    // Settings 的 ScrollState 被暂存→恢复；否则 Settings 离开组合后重建，滚动回到顶部。
    val screenStateHolder = rememberSaveableStateHolder()
    var fontScale by remember { mutableFloatStateOf(FontSizeStore.getScale(ctx)) }
    when (nav.lastOrNull()) {
        Screen.Search -> {
            screenStateHolder.SaveableStateProvider("Search") {
                SearchScreen(
                    charFile = vm.session?.charFile ?: "",
                    onBack = ::navBack,
                    onOpenSession = { id ->
                        vm.openSession(id)
                        scope.launch { drawerState.snapTo(DrawerValue.Closed) }
                        navBack()
                    },
                )
            }
            return
        }
        Screen.FontSize -> {
            screenStateHolder.SaveableStateProvider("FontSize") {
                FontSizeScreen(
                    onBack = {
                        navBack()
                        fontScale = FontSizeStore.getScale(ctx)
                    },
                    currentScale = fontScale,
                )
            }
            return
        }
        Screen.PromptLog -> {
            screenStateHolder.SaveableStateProvider("PromptLog") {
                PromptLogScreen(onBack = ::navBack)
            }
            return
        }
        Screen.DataMgmt -> {
            screenStateHolder.SaveableStateProvider("DataMgmt") {
                DataManagementScreen(onBack = {
                    navBack()
                    vm.refreshAfterDataManagement()
                })
            }
            return
        }
        Screen.ApiConfig -> {
            screenStateHolder.SaveableStateProvider("ApiConfig") {
                ApiConfigScreen(onBack = {
                    navBack()
                    vm.reloadApiConfig()
                })
            }
            return
        }
        Screen.WorldBooks -> {
            screenStateHolder.SaveableStateProvider("WorldBooks") {
                WorldBookListScreen(onBack = {
                    navBack()
                    vm.reloadPromptData()
                })
            }
            return
        }
        Screen.Characters -> {
            screenStateHolder.SaveableStateProvider("Characters") {
                CharacterListScreen(onBack = {
                    navBack()
                    vm.refreshCurrentCard()
                })
            }
            return
        }
        Screen.Presets -> {
            screenStateHolder.SaveableStateProvider("Presets") {
                PresetListScreen(onBack = {
                    navBack()
                    vm.reloadPromptData()
                })
            }
            return
        }
        Screen.Settings -> {
            screenStateHolder.SaveableStateProvider("Settings") {
                SettingsScreen(
                    onBack = ::navBack,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onNavigateToPresets = { nav.add(Screen.Presets) },
                    onNavigateToCharacters = { nav.add(Screen.Characters) },
                    onNavigateToWorldBooks = { nav.add(Screen.WorldBooks) },
                    onNavigateToApiConfig = { nav.add(Screen.ApiConfig) },
                    onNavigateToDataManagement = { nav.add(Screen.DataMgmt) },
                    onNavigateToFontSize = { nav.add(Screen.FontSize) },
                    onNavigateToPromptLog = { nav.add(Screen.PromptLog) },
                    onNavigateToExtensions = { nav.add(Screen.Extensions) },
                    onNavigateToAbout = { nav.add(Screen.About) },
                )
            }
            return
        }
        Screen.Extensions -> {
            screenStateHolder.SaveableStateProvider("Extensions") {
                ExtensionsScreen(onBack = {
                    navBack()
                    vm.refreshExtensionSlots()
                })
            }
            return
        }
        Screen.About -> {
            screenStateHolder.SaveableStateProvider("About") {
                AboutScreen(onBack = ::navBack)
            }
            return
        }
        null -> {}
    }

    val curProv = vm.apiConfig.providers.find { it.id == vm.apiConfig.currentModel.substringBefore("::") }
    val curModelId = if (curProv != null) vm.apiConfig.currentModel.substringAfter("::", "") else ""

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerState = drawerState, drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
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
                    onDeleteSession = { id -> deleteSessionId = id },
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    ChatTopBar(
                        title = (vm.session?.charName ?: "").ifBlank { stringResource(R.string.default_character) },
                        subtitle = if (curModelId.isBlank()) stringResource(R.string.no_model_selected)
                                   else "$curModelId (${curProv?.name ?: ""})",
                        onDrawer = { scope.launch { drawerState.open() } },
                        onNewChat = { vm.newChat() },
                    )
                },
                bottomBar = {
                    ChatBottomArea(
                        text = vm.inputText,
                        onTextChange = { vm.inputText = it },
                        attachments = vm.attachments,
                        onRemoveAttachment = { vm.attachments = vm.attachments - it },
                        showAttachment = showAttachment,
                        onToggleAttachment = { showAttachment = !showAttachment },
                        currentModelId = curModelId,
                        generating = vm.generating,
                        onStop = { vm.stopGenerate() },
                        onSend = {
                            val outcome = vm.sendMessage()
                            if (outcome != ChatViewModel.SendOutcome.SKIPPED) keyboardController?.hide()
                            handleOutcome(outcome)
                        },
                        onSelectModel = { showModelPicker = true },
                        onCamera = {
                            val photoFile = File(ctx.cacheDir, "photos/photo_${System.currentTimeMillis()}.jpg")
                            photoFile.parentFile?.mkdirs()
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", photoFile)
                            cameraImageUri = uri
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
                val msgs: List<ChatMessage> = if (overlays.isEmpty()) pagedBase else {
                    val substituted = pagedBase.map { overlays[it.ts] ?: it }
                    // overlay 里分页尚未纳入的行（刚开始生成、还没落盘的新 assistant，其 ts 最大）按序追加到末尾。
                    // 仅当已加载窗口"确实稳定地"未抵达底部时才不追加（长会话滚上去、refresh 收窗到上方锚点）——
                    // 此时该消息在视口下方之外，不该错插进当前窗口尾部。
                    // 但 refresh/append 加载中属过渡态：append.endOfPaginationReached 会被临时重置为 false，
                    // 若此时也判定"非底部"，生成完成那次写库触发的 refresh 会让刚完成的消息瞬间闪掉再回来。
                    // 故加载中一律按抵达底部处理，保持追加。
                    val append = lazyMessages.loadState.append
                    val windowAtBottom = !usePaging || append.endOfPaginationReached ||
                        append is LoadState.Loading || lazyMessages.loadState.refresh is LoadState.Loading
                    val extra = if (!windowAtBottom) emptyList()
                        else overlays.values.filter { ov -> pagedBase.none { it.ts == ov.ts } }.sortedBy { it.ts }
                    substituted + extra
                }
                // rememberUpdatedState：快照 Flow 的 collect lambda 里引用 msgs，需要始终读到最新值
                val msgsNow by rememberUpdatedState(msgs)
                // overlay 收敛：分页已把该 ts 的最终内容补齐、且该行不在生成中时撤下 overlay
                //（避免"异步写库→分页刷新"时间差造成的空帧/陈旧内容闪烁）
                val settledOverlayTs = overlays.values.filter { ov ->
                    !(vm.generating && ov.ts == genTs) &&
                        pagedBase.any { it.ts == ov.ts && it.content == ov.content && it.reasoning == ov.reasoning }
                }.map { it.ts }
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
                                            val midRegen = i + 1 < msgs.lastIndex && msgs[i + 1].role == "assistant"
                                            handleOutcome(vm.regenerateAfterUser(msg.ts), scroll = !midRegen)
                                        },
                                        onMore = { menuTargetIdx = msg.ts },
                                        scale = fontScale,
                                        avatarBitmap = vm.userAvatarBitmap,
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
                                        onRegenerate = { handleOutcome(vm.regenerateAi(msg.ts), scroll = i == msgs.lastIndex) },
                                        onMore = { menuTargetIdx = msg.ts },
                                        onPrevAlt = { switchAltAnchored(-1) },
                                        onNextAlt = { switchAltAnchored(+1) },
                                        scale = fontScale,
                                        regexScripts = vm.displayRegexScripts,
                                        depth = msgs.size - 1 - i,
                                        userName = vm.userName,
                                        charName = vm.currentCard?.name ?: vm.session?.charName ?: "",
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
                    ScrollNavButtons(
                        visible = msgs.isNotEmpty() && scrollCtrl.showNavButtons,
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

    // ── 消息操作菜单 ──
    val menuTs = menuTargetIdx
    // 与渲染一致地取目标：overlay（流式/乐观态）优先，回退到内存会话
    val menuMsg = menuTs?.let { ts -> vm.overlays[ts] ?: vm.session?.messages?.firstOrNull { it.ts == ts } }
    if (menuTs != null && menuMsg != null) {
        MessageMenu(
            onDismiss = { menuTargetIdx = null },
            onSelectCopy = {
                selectCopyText = menuMsg.content
                menuTargetIdx = null
            },
            onEdit = {
                if (!vm.generating) editTargetIdx = menuTs
                menuTargetIdx = null
            },
            onDelete = {
                val wasLast = menuMsg.ts == vm.session?.messages?.lastOrNull()?.ts
                vm.deleteMessage(menuTs)
                // 删除末条（整条或当前分支）导致内容高度骤变，钉回底部避免落点漂移
                if (wasLast) scrollToBottomTrigger++
                menuTargetIdx = null
            },
        )
    }

    // ── 选择复制对话框 ──
    selectCopyText?.let { txt ->
        AlertDialog(
            onDismissRequest = { selectCopyText = null },
            title = { Text(stringResource(R.string.select_copy)) },
            text = {
                SelectionContainer {
                    Text(txt, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = {
                TextButton(onClick = { copyText(txt); selectCopyText = null }) {
                    Text(stringResource(R.string.copy_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { selectCopyText = null }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    // ── 编辑消息对话框 ──
    val editTs = editTargetIdx
    val editMsg = editTs?.let { ts -> vm.overlays[ts] ?: vm.session?.messages?.firstOrNull { it.ts == ts } }
    if (editTs != null && editMsg != null) {
        var editText by remember(editTs) { mutableStateOf(editMsg.content) }
        AlertDialog(
            onDismissRequest = { editTargetIdx = null },
            title = { Text(stringResource(R.string.edit_message)) },
            text = {
                OutlinedTextField(editText, { editText = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 10)
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateMessage(editTs, editText)
                    editTargetIdx = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { editTargetIdx = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // ── 删除会话对话框 ──
    deleteSessionId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteSessionId = null },
            title = { Text(stringResource(R.string.delete_chat_title)) },
            text = { Text(stringResource(R.string.delete_chat_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSession(id)
                    deleteSessionId = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSessionId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // ── 模型选择面板 ──
    if (showModelPicker) {
        ModelPickerSheet(
            providers = vm.apiConfig.providers,
            currentModel = vm.apiConfig.currentModel,
            onSelect = { providerId, modelId ->
                vm.selectModel(providerId, modelId)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
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
}

