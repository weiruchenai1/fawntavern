package me.rerere.stapp.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import java.io.File
import kotlinx.coroutines.launch
import me.rerere.stapp.R
import me.rerere.stapp.data.settings.FontSizeStore
import me.rerere.stapp.data.settings.ThemeMode
import me.rerere.stapp.ui.api.ApiConfigScreen
import me.rerere.stapp.ui.character.CharacterListScreen
import me.rerere.stapp.ui.preset.PresetListScreen
import me.rerere.stapp.ui.settings.DataManagementScreen
import me.rerere.stapp.ui.settings.FontSizeScreen
import me.rerere.stapp.ui.settings.SettingsScreen
import me.rerere.stapp.ui.hooks.ImeLazyListAutoScroller
import me.rerere.stapp.ui.worldbook.WorldBookListScreen
import me.rerere.stapp.ui.components.Space8
import me.rerere.stapp.ui.components.Space16

/** 聊天之上的全屏页面，以返回栈方式叠放（栈顶显示，返回键弹出） */
private enum class Screen {
    Settings, Presets, Characters, WorldBooks, ApiConfig, DataMgmt, FontSize, Search,
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
    // ── 消息操作弹窗状态 ──
    var menuTargetIdx by remember { mutableStateOf<Int?>(null) }
    var editTargetIdx by remember { mutableStateOf<Int?>(null) }
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

    // ── 全屏页面：渲染栈顶 ──
    var fontScale by remember { mutableFloatStateOf(FontSizeStore.getScale(ctx)) }
    when (nav.lastOrNull()) {
        Screen.Search -> {
            SearchScreen(
                charFile = vm.session?.charFile ?: "",
                onBack = ::navBack,
                onOpenSession = { id ->
                    vm.openSession(id)
                    // 搜索是从抽屉进入的、抽屉此刻仍为打开状态；直达会话应落在聊天页而不是抽屉
                    scope.launch { drawerState.snapTo(DrawerValue.Closed) }
                    navBack()
                },
            )
            return
        }
        Screen.FontSize -> {
            FontSizeScreen(
                onBack = {
                    navBack()
                    fontScale = FontSizeStore.getScale(ctx)
                },
                currentScale = fontScale,
            )
            return
        }
        Screen.DataMgmt -> {
            DataManagementScreen(onBack = {
                navBack()
                vm.refreshAfterDataManagement()
            })
            return
        }
        Screen.ApiConfig -> {
            ApiConfigScreen(onBack = {
                navBack()
                vm.reloadApiConfig()
            })
            return
        }
        Screen.WorldBooks -> {
            WorldBookListScreen(onBack = {
                navBack()
                // 世界书内容可能被编辑，当前角色关联的书需要重载
                vm.reloadPromptData()
            })
            return
        }
        Screen.Characters -> {
            CharacterListScreen(onBack = {
                navBack()
                // 列表/编辑器里可能改了当前角色的字段或图片
                vm.refreshCurrentCard()
            })
            return
        }
        Screen.Presets -> {
            PresetListScreen(onBack = {
                navBack()
                // 预设/独立正则脚本可能被增删改
                vm.reloadPromptData()
            })
            return
        }
        Screen.Settings -> {
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
            )
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
                    )
                }
            ) { padding ->
                val msgs = vm.session?.messages ?: emptyList()
                val listState = rememberLazyListState()
                val followScope = rememberCoroutineScope()
                // 长生命周期跟随协程读取的最新值（协程不随这些值重启）。
                // 只有生成目标是末条消息时才自动跟随钉底；重答中间消息时原地生成，视口不动
                val generatingAtEndNow by rememberUpdatedState(vm.generating && vm.generatingIdx == msgs.lastIndex)
                val lastUserIdxNow by rememberUpdatedState(msgs.indexOfLast { it.role == "user" })
                // 末条消息正文是否还是空的 = 纯思考阶段（思考内容默认折叠，屏上没有可读的正文）
                val lastMsgContentBlankNow by rememberUpdatedState(msgs.lastOrNull()?.content.isNullOrBlank())

                val density = LocalDensity.current
                // 贴底判定，带 80dp 缓冲：reasoning 行等一帧内插入把底部顶出视口时仍算贴底
                val bottomSlackPx = remember(density) { with(density) { 80.dp.toPx() } }
                fun isAtBottom(): Boolean {
                    val layout = listState.layoutInfo
                    if (layout.totalItemsCount == 0) return true
                    val last = layout.visibleItemsInfo.lastOrNull() ?: return true
                    if (last.index < layout.totalItemsCount - 2) return false
                    val overshoot = last.offset + last.size - layout.viewportEndOffset
                    return overshoot <= bottomSlackPx
                }
                // 内容是否溢出视口（不足一屏时无需跟随、也不显示向下按钮）
                fun contentOverflows(): Boolean {
                    val layout = listState.layoutInfo
                    if (layout.totalItemsCount == 0) return false
                    return listState.canScrollForward || listState.firstVisibleItemIndex > 0 ||
                        listState.firstVisibleItemScrollOffset > 0
                }
                // 提问顶部是否已抵达顶线（视口顶 8dp 内）
                val topLinePx = remember(density) { with(density) { 8.dp.toPx() } }
                fun lastUserMsgTopReached(): Boolean {
                    val idx = lastUserIdxNow
                    if (idx < 0) return false
                    if (listState.firstVisibleItemIndex > idx) return true   // 提问已滚出视口上方
                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }
                        ?: return false
                    return info.offset <= topLinePx
                }
                // ── 状态机 ──
                // autoFollow: 是否自动滚到底部跟随。userRequestedFollow: 用户显式要求持续跟随（点向下按钮/下划到底），
                //   为 true 时跳过顶线停跟。
                var autoFollow by remember { mutableStateOf(true) }
                var userRequestedFollow by remember { mutableStateOf(false) }
                // “向下”按钮可见性是纯派生值：离底超过缓冲自动出现，
                // 贴底自动消失，不需要在各个事件里手动开关
                val showScrollDown by remember {
                    derivedStateOf { contentOverflows() && !isAtBottom() }
                }

                // AI 输出新内容 → 若 autoFollow 则滚到底；到顶线且未显式跟随 → 顶线停跟
                //（停跟后向下按钮由派生的 showScrollDown 在离底超过缓冲时自动出现）
                LaunchedEffect(listState) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo }.collect {
                        // 手势永远优先：拖拽/惯性滚动期间绝不钉底
                        if (!generatingAtEndNow || !autoFollow || listState.isScrollInProgress) return@collect
                        // 停跟的意义是"从头阅读正在输出的正文"。思考阶段（正文为空）没有可读内容，
                        // 不允许停跟——否则发送时键盘还开着、视口被压小，提问顶部很容易越过小视口
                        // 顶线而误触发停跟，之后思考行/正文出现就再也没人钉底了。
                        if (!userRequestedFollow && !lastMsgContentBlankNow && contentOverflows() && lastUserMsgTopReached()) {
                            autoFollow = false          // 顶线停跟：提问钉在顶线，回复在折线下继续输出
                        } else {
                            listState.requestScrollToItem(listState.layoutInfo.totalItemsCount + 5)
                        }
                    }
                }
                // 用户手势拖拽结束：主动下划回到底部 → 恢复跟随；其余情况保持停跟。
                // 用 DragInteraction 而非 isScrollInProgress，排除键盘收起触发的程序化滚动。
                var dragging by remember { mutableStateOf(false) }
                LaunchedEffect(listState) {
                    var dragStartIdx = 0
                    var dragStartOff = 0
                    launch {
                        listState.interactionSource.interactions.collect { interaction ->
                            when (interaction) {
                                is DragInteraction.Start -> {
                                    dragging = true
                                    dragStartIdx = listState.firstVisibleItemIndex
                                    dragStartOff = listState.firstVisibleItemScrollOffset
                                    // 手指开始拖动立即断开跟随：否则生成中每帧钉底会把列表拽回，根本无法上划看历史
                                    autoFollow = false
                                    userRequestedFollow = false
                                }
                                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                                    // 零位移拖拽不会经历 isScrollInProgress 翻转，settle 收集器不触发，
                                    // 必须在抬手时清除，否则 dragging 悬挂为 true
                                    if (!listState.isScrollInProgress) dragging = false
                                }
                                else -> {}
                            }
                        }
                    }
                    launch {
                        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                            if (!scrolling && dragging) {
                                dragging = false
                                // 只有"确实向下划过"且落在底部才恢复跟随：轻触、小幅上划（在 80dp
                                // 缓冲内也算贴底）都不算，否则生成中随手一碰就又被拽回底部
                                val scrolledDown = listState.firstVisibleItemIndex > dragStartIdx ||
                                    (listState.firstVisibleItemIndex == dragStartIdx &&
                                        listState.firstVisibleItemScrollOffset > dragStartOff)
                                if (scrolledDown && isAtBottom()) {
                                    if (generatingAtEndNow) { autoFollow = true; userRequestedFollow = true }
                                } else {
                                    autoFollow = false
                                    userRequestedFollow = false
                                }
                            }
                        }
                    }
                }

                // 钉到底部：反复请求直到连续几帧稳定贴底。Markdown 是异步解析的，远距离跳转时
                // 新组合的消息要过几帧才长到真实高度，固定次数连钉会停在半路。
                // 用户拖拽接管、或生成中触发了顶线停跟时立即让位。
                suspend fun pinToBottom() {
                    var stable = 0
                    var frames = 0
                    while (frames++ < 60 && stable < 3) {
                        // dragging 需与 isScrollInProgress 同真才让位：零位移拖拽不经历
                        // isScrollInProgress 翻转，settle 收集器不清除，悬挂的 dragging
                        // 会让这里静默不钉（表现为切分支后落点随机）
                        if ((dragging && listState.isScrollInProgress) || (generatingAtEndNow && !autoFollow)) return
                        val info = listState.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull()
                        val atBottomNow = last != null && last.index == info.totalItemsCount - 1 &&
                            last.offset + last.size <= info.viewportEndOffset
                        if (atBottomNow) {
                            stable++
                        } else {
                            stable = 0
                            listState.requestScrollToItem(info.totalItemsCount + 5)
                        }
                        withFrameNanos { }
                    }
                }

                // 用户发送、重试、删除末条：回到底部并重置状态（提问在底部起步，随回复增长再被顶到线）
                LaunchedEffect(scrollToBottomTrigger) {
                    if (msgs.isNotEmpty()) {
                        autoFollow = true
                        userRequestedFollow = false
                        pinToBottom()
                    }
                }
                // 打开/切换会话：回到底部并重置
                LaunchedEffect(vm.session?.id) {
                    autoFollow = true; userRequestedFollow = false
                    if (msgs.isNotEmpty()) pinToBottom()
                }
                // 生成结束：仍在跟随且生成目标是末条消息则钉住底部（正文切 Markdown、工具栏出现会改高度）
                LaunchedEffect(vm.generating) {
                    if (!vm.generating && msgs.isNotEmpty() && autoFollow &&
                        vm.generatingIdx == msgs.lastIndex) pinToBottom()
                }
                // 键盘弹出只在贴底时跟随上移；在上方读历史时视口保持不动
                ImeLazyListAutoScroller(lazyListState = listState, shouldFollow = ::isAtBottom)

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
                            state = listState,
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
                                            handleOutcome(vm.regenerateAfterUser(i), scroll = !midRegen)
                                        },
                                        onMore = { menuTargetIdx = i },
                                        scale = fontScale,
                                        avatarBitmap = vm.userAvatarBitmap,
                                    )
                                } else {
                                    // 切分支后重新锚定：内容一换，旧的像素锚定会落在新文本的任意位置。
                                    // 末条消息或人在底部附近：钉回底部——切换按钮原地不动，且新分支
                                    // 更短时不会触发 LazyColumn"填满视口"的回拉（忽高忽低的来源）。
                                    // 其余历史消息：钉住下一条消息（i+1）的顶部，即本条底部/切换按钮行。
                                    // 不钉本条顶部——那个偏移与本条新高度相关：滚进长消息内部后切到更
                                    // 短的分支时偏移越界、被归一化到任意位置；新分支变短时还可能触发
                                    // 底部"填满视口"回拉，落点看起来随机。锚定 i+1 则按钮与下方内容
                                    // 纹丝不动，长度变化全部向上生长，与末条/贴底分支行为一致。
                                    fun switchAltAnchored(dir: Int) {
                                        val layoutBefore = listState.layoutInfo
                                        val iInfo = layoutBefore.visibleItemsInfo.firstOrNull { it.index == i }
                                        // i+1 可见用实测顶部；不可见（按钮贴着视口底）用本条底 + 间距推算
                                        val nextTop = layoutBefore.visibleItemsInfo.firstOrNull { it.index == i + 1 }?.offset
                                            ?: iInfo?.let { it.offset + it.size + layoutBefore.mainAxisItemSpacing }
                                        val nearBottom = isAtBottom()
                                        if (!vm.switchAlt(i, dir)) return
                                        if (i == msgs.lastIndex || nearBottom) {
                                            // 底部锚点可见时把锚点原地钉住（同步请求，与新内容同帧生效）：
                                            // 分支长短变化全部发生在锚点上方，切换按钮到输入栏的距离纹丝
                                            // 不动——即使当前离精确底部还差几十 dp 也不会被吸附着上移。
                                            // 锚点不可见（离底较远）才退回"吸附到底"。
                                            val info = listState.layoutInfo
                                            val anchor = info.visibleItemsInfo.lastOrNull()
                                                ?.takeIf { it.index == info.totalItemsCount - 1 }
                                            if (anchor != null) {
                                                listState.requestScrollToItem(anchor.index, -anchor.offset)
                                            } else {
                                                listState.requestScrollToItem(info.totalItemsCount + 5)
                                                followScope.launch { pinToBottom() }
                                            }
                                        } else if (nextTop != null) {
                                            listState.requestScrollToItem(i + 1, -nextTop)
                                        }
                                    }
                                    AIMsg(
                                        msg = msg,
                                        isStreaming = vm.generating && i == vm.generatingIdx,
                                        onCopy = { copyText(msg.content) },
                                        onRegenerate = { handleOutcome(vm.regenerateAi(i), scroll = i == msgs.lastIndex) },
                                        onMore = { menuTargetIdx = i },
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

                    // ── 向下按钮 ──（顶线停跟或用户上滑后出现；生成中带加载环，结束为纯向下图标）
                    // msgs 为空时 LazyColumn 不在组合中，listState.layoutInfo 停留在上个会话的
                    // 旧值，派生的 showScrollDown 会误报——新建空会话（默认角色无开场白）不显示
                    if (msgs.isNotEmpty() && showScrollDown) {
                        Box(
                            Modifier.align(Alignment.BottomCenter)
                                // Scaffold 的 content 铺满全屏、底栏叠在上层：必须加上底栏高度才不会被盖住
                                .padding(bottom = Space16 + padding.calculateBottomPadding())
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable {
                                    // 点击向下按钮：钉到底部（按钮随贴底自动消失）；末条生成中则恢复持续跟随
                                    followScope.launch { pinToBottom() }
                                    if (generatingAtEndNow) { autoFollow = true; userRequestedFollow = true }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (vm.generating) {
                                CircularProgressIndicator(
                                    Modifier.size(40.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Icon(Lucide.ChevronDown, stringResource(R.string.scroll_to_bottom),
                                Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
    }

    // ── 消息操作菜单 ──
    val menuIdx = menuTargetIdx
    val menuMsg = menuIdx?.let { vm.session?.messages?.getOrNull(it) }
    if (menuIdx != null && menuMsg != null) {
        MessageMenu(
            onDismiss = { menuTargetIdx = null },
            onSelectCopy = {
                selectCopyText = menuMsg.content
                menuTargetIdx = null
            },
            onEdit = {
                if (!vm.generating) editTargetIdx = menuIdx
                menuTargetIdx = null
            },
            onDelete = {
                val wasLast = menuIdx == (vm.session?.messages?.lastIndex ?: -1)
                vm.deleteMessage(menuIdx)
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
    val editIdx = editTargetIdx
    val editMsg = editIdx?.let { vm.session?.messages?.getOrNull(it) }
    if (editIdx != null && editMsg != null) {
        var editText by remember(editIdx) { mutableStateOf(editMsg.content) }
        AlertDialog(
            onDismissRequest = { editTargetIdx = null },
            title = { Text(stringResource(R.string.edit_message)) },
            text = {
                OutlinedTextField(editText, { editText = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 10)
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateMessage(editIdx, editText)
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

