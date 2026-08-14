package me.rerere.fawntavern.ui.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.PromptContextLoader
import me.rerere.fawntavern.data.api.ApiConfigStore
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.BuiltInTool
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.api.supportsBuiltInTool
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatRepository
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.preset.StPreset
import me.rerere.fawntavern.data.preset.toCharRegex
import me.rerere.fawntavern.data.speech.TtsUiState
import me.rerere.fawntavern.data.settings.UserProfileStore
import me.rerere.fawntavern.data.settings.UserAvatarStore
import me.rerere.fawntavern.data.settings.PromptLogStore
import me.rerere.fawntavern.data.settings.GlobalVariableStore
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.domain.ConversationOps
import me.rerere.fawntavern.domain.GenerationController
import me.rerere.fawntavern.domain.GenerationActionGuard
import me.rerere.fawntavern.domain.PromptBuilder
import me.rerere.fawntavern.domain.PromptLog
import me.rerere.fawntavern.extension.BuiltinExtensions
import me.rerere.fawntavern.extension.ExtensionStore
import me.rerere.fawntavern.extension.GenerationContext
import me.rerere.fawntavern.extension.GenerationLifecycle
import me.rerere.fawntavern.extension.HostServices
import me.rerere.fawntavern.extension.QuickReply
import me.rerere.fawntavern.extension.QuickReplyProvider

private const val CHAT_VIEW_MODEL_TAG = "ChatViewModel"

/**
 * 聊天状态容器：只负责持有 UI 状态、调度协程和落盘。
 * 业务逻辑在 domain 层：Prompt 拼装 → [PromptBuilder]，
 * 会话/消息纯变换 → [ConversationOps]，流式生成 → [GenerationController]。
 * 生成协程运行在 viewModelScope —— Activity 因深色模式/语言切换等重建时不中断，状态不丢失。
 * UI（ChatScreen）只读状态、调方法，不直接改。
 */
internal class ChatViewModel(app: Application) : AndroidViewModel(app) {

    /** 发送/重答的结果：UI 据此决定是否弹"先选模型"提示 */
    enum class SendOutcome { STARTED, NO_MODEL, SKIPPED, FILE_TOO_LARGE }

    /** 发送时附件落盘失败的提示（一次性消费：UI 弹完清空）；过大在 SendOutcome 里同步拦截，这里是兜底 */
    var sendError by mutableStateOf<String?>(null); private set

    // ── 状态（写入只经由本类方法） ──
    private val modelController = ChatModelController(AndroidChatModelDataSource(app))
    var apiConfig by mutableStateOf(ApiConfigStore.loadConfig(app)); private set
    private val uiSettingsController = ChatUiSettingsController(AndroidChatUiSettingsDataSource(app))
    var uiSettings by mutableStateOf(uiSettingsController.load()); private set
    /** 当前模型的思考预算档位（按模型记忆，随选模型切换）；AUTO = 不下发任何思考字段 */
    var reasoning by mutableStateOf(modelController.reasoning(apiConfig.currentModel)); private set
    var sessions by mutableStateOf<List<ChatSession>>(emptyList()); private set
    var session by mutableStateOf<ChatSession?>(null); private set
    var currentCard by mutableStateOf<CharacterCard?>(null); private set
    /** 当前角色卡的图片（无图为 null，UI 回退到占位图标） */
    var charImageBitmap by mutableStateOf<Bitmap?>(null); private set
    var generating by mutableStateOf(false); private set
    /**
     * 覆盖在分页列表之上的内存消息（按 ts 索引）：承载流式生成的实时内容、以及分支切换/编辑的
     * 乐观即时反馈。写库是异步的（DB→Room 失效→分页重刷有几帧时间差），overlay 在此期间顶替显示，
     * 待分页把该 ts 的最终内容补齐后由 UI 调 [clearOverlay] 撤下——避免空帧/陈旧内容闪烁，
     * 并让滚动锚定像旧同步逻辑一样在下一帧就能读到新内容。
     */
    var overlays by mutableStateOf<Map<Long, ChatMessage>>(emptyMap()); private set
    // selectModel 写的是持久化 store，displayModelSpec 读 store，二者非 state——自增它以强制 UI 刷新
    var modelRevision by mutableIntStateOf(0); private set
    /** 当前/最近一次生成的目标消息 ts（重答时指向被重答的消息），null = 尚未生成过 */
    var genTargetTs by mutableStateOf<Long?>(null); private set
    var userName by mutableStateOf(UserProfileStore.getName(app)); private set
    var userAvatarBitmap by mutableStateOf<Bitmap?>(null); private set
    // 当前角色关联的世界书/预设（随角色卡切换加载，生成时进入 Prompt 拼装）
    var activeWorldBooks by mutableStateOf<List<WorldBook>>(emptyList()); private set
    var activePreset by mutableStateOf<StPreset?>(null); private set
    var promptContextFailures by mutableStateOf<List<PromptContextLoader.LoadFailure>>(emptyList())
        private set
    private var loadedPromptCharFile: String? = null
    private var promptContextRevision = 0L
    // 输入草稿放这里，Activity 重建后不丢。BTF2 的 TextFieldState 即单一事实源，
    // 输入框与展开面板持有同一实例，两边改动天然同步，不需要 onTextChange 回写
    val inputState = TextFieldState()
    /** [inputState] 的字符串视图：VM 内部与旧调用方按 String 读写，光标落到末尾 */
    var inputText: String
        get() = inputState.text.toString()
        set(value) = inputState.setTextAndPlaceCursorAtEnd(value)
    var attachments by mutableStateOf(listOf<Attachment>())
    /** 正在编辑的消息 ts：非 null = 输入框处于编辑态，发送走更新而非追加 */
    var editingTs by mutableStateOf<Long?>(null); private set

    /** 启用的快捷回复（UI 插槽扩展提供），随扩展配置刷新 */
    var quickReplies by mutableStateOf<List<QuickReply>>(emptyList()); private set

    private val webSearchSettingsController =
        ChatWebSearchSettingsController(AndroidChatWebSearchSettingsDataSource(app))
    private var webSearchSettings by mutableStateOf(webSearchSettingsController.load())
    val searchEnabled: Boolean
        get() = webSearchSettings.enabled
    val searchProviderIndex: Int
        get() = webSearchSettings.selectedIndex
    val searchServices
        get() = webSearchSettings.services
    val searchProviderName: String
        get() = webSearchSettings.providerName
    val builtInSearchAvailable: Boolean
        get() {
            modelRevision
            val (provider, modelId) = currentProviderAndModel() ?: return false
            return provider.model(modelId)?.supportsBuiltInTool(BuiltInTool.SEARCH, provider) == true
        }
    val builtInSearchEnabled: Boolean
        get() {
            modelRevision
            val (provider, modelId) = currentProviderAndModel() ?: return false
            return BuiltInTool.SEARCH in (provider.model(modelId)?.tools ?: emptySet())
        }
    /** 正在朗读的 AI 消息 ts（点击朗读图标后置位，读完/停止时清空） */
    var speakingTs by mutableStateOf<Long?>(null); private set
    /** TTS 朗读实时状态（悬浮工具栏展示/控制用），随引擎播放同步更新 */
    var ttsUi by mutableStateOf(TtsUiState()); private set

    private val ttsControllerDelegate = lazy {
        ChatTtsController(ctx).also { controller ->
            viewModelScope.launch { controller.ui.collect { ttsUi = it } }
            viewModelScope.launch { controller.speakingTs.collect { speakingTs = it } }
        }
    }
    private val ttsController by ttsControllerDelegate

    /** 当前预设私有的正则（关联该预设的聊天才生效），转成引擎统一类型 */
    private val presetRegex: List<CharRegex>
        get() = activePreset?.regexScripts?.map { it.toCharRegex() } ?: emptyList()

    /** 显示侧正则：角色卡内嵌 + 当前预设私有 */
    val displayRegexScripts: List<CharRegex>
        get() = (currentCard?.regexScripts ?: emptyList()) + presetRegex

    private val generation = GenerationController()
    private val ctx: Application get() = getApplication()
    private val generationCoordinator by lazy {
        ChatGenerationCoordinator(
            scope = viewModelScope,
            stopCurrent = generation::stop,
            onRunningChanged = { generating = it },
            onFailure = { error ->
                Log.e(CHAT_VIEW_MODEL_TAG, "生成任务失败", error)
                sendError = ctx.getString(R.string.chat_generation_failed_fmt, error.message.orEmpty())
            },
        )
    }
    private val messageCoordinator by lazy { ChatMessageCoordinator(ctx) }
    private val attachmentCoordinator by lazy {
        ChatAttachmentCoordinator(AndroidChatAttachmentDataSource(ctx))
    }
    private val sessionCoordinator by lazy {
        ChatSessionCoordinator(AndroidChatSessionDataSource(ctx))
    }
    private val searchTool by lazy { ChatSearchTool(ctx) }
    private val titleGenerator by lazy { ChatTitleGenerator(ctx) }
    private val promptAssembler by lazy { ChatPromptAssembler(ctx) }

    /**
     * 当前会话消息的分页流（Paging 3）。随 [session] 的 id 切换：初始加载偏移定位到最后一页，
     * 天然停在底部；后续任何 DB 写入由 Room 使数据源失效自动重刷。未落盘的新会话（仅开场白）
     * 分页为空，UI 回退到 [session] 内存消息显示开场白。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedMessages: Flow<PagingData<ChatMessage>> =
        snapshotFlow { session?.id }
            .flatMapLatest { id ->
                if (id == null) flowOf(PagingData.empty())
                else flow {
                    // 切会话瞬间先发一个空页：清掉 collectAsLazyPagingItems 里残留的上个会话快照，
                    // 让 UI 立刻回退到新会话的内存消息（避免旧会话消息在新标题下闪现），随后加载真实分页
                    emit(PagingData.empty())
                    val count = ChatRepository.messageCount(ctx, id)
                    // 让最新一页先加载：初始偏移取 count-pageSize（pageSize=60），不足一页则从头
                    val initialKey = (count - 60).takeIf { it > 0 }
                    emitAll(ChatRepository.messagesPaged(ctx, id, initialKey))
                }
            }
            .cachedIn(viewModelScope)

    init {
        // 登记内置官方扩展（幂等）
        BuiltinExtensions.registerAll()
        refreshExtensionSlots()
        // Prompt 调试日志开关：把持久化设置同步到内存 sink（关闭时生成不记录）
        PromptLog.enabled = PromptLogStore.isEnabled(app)
        // 会话列表来自 Repository 的 Flow：任何 save/delete/clear 后自动刷新
        viewModelScope.launch {
            val defaultName = CharacterRepository.ensureDefaultCard(ctx, ctx.getString(R.string.default_character))
            if (ChatRepository.count(ctx) == 0) {
                session = ConversationOps.newSession(loadCard(defaultName), defaultName, defaultName)
            }
            ChatRepository.sessionsFlow(ctx).collect {
                sessions = it
                if (session == null) {
                    // 应用启动时新建对话：每次启动都从空白对话开始（内存态，发首条消息才落盘）
                    session = if (uiSettings.newChatOnLaunch) {
                        val card = loadCard(defaultName)
                        ConversationOps.newSession(card, defaultName, defaultName)
                    } else it.firstOrNull()?.let { summary -> ChatRepository.get(ctx, summary.id) }
                }
            }
        }
        // 会话的角色变化时加载对应角色卡，并恢复该角色记忆的模型
        viewModelScope.launch {
            snapshotFlow { session?.charFile }.collectLatest { file ->
                val charFile = file.orEmpty()
                val revision = invalidatePromptContext()
                val loaded = PromptContextLoader.load(ctx, charFile)
                val image = if (charFile.isBlank()) null else loadCharImage(charFile)
                if (session?.charFile.orEmpty() != charFile) return@collectLatest
                applyPromptContext(loaded, image, revision)
                // 恢复该角色的模型：角色记忆 > 默认模型聊天卡片
                val spec = modelController.effectiveModelSpec(currentCard?.name, apiConfig).orEmpty()
                reasoning = modelController.reasoning(spec)
            }
        }
        // 切换会话即清空 overlay：overlay 按 ts 索引，而 ts 仅在会话内唯一，跨会话残留会误覆盖
        // 另一会话里同 ts 的消息。生成中会话切换被禁用（generating 时 openSession/openCharacter 直接返回），
        // 故生成用的 overlay 不会被此清除。
        viewModelScope.launch {
            snapshotFlow { session?.id }.collect { overlays = emptyMap() }
        }
        reloadUserProfile()
    }

    override fun onCleared() {
        if (ttsControllerDelegate.isInitialized()) ttsController.release()
    }

    // ── 配置 / 用户资料 ──

    /** 当前页面的模型：角色记忆 > ROLE_CHAT > apiConfig.currentModel 回退，全空时返回 null */
    fun displayModelSpec(): String? = modelController.effectiveModelSpec(currentCard?.name, apiConfig)

    /** 从 API 配置页返回时刷新：若模型仍在则保持，若模型被删除/禁用则切到全局配置的 currentModel 兜底 */
    fun reloadApiConfig() {
        apiConfig = ApiConfigStore.loadConfig(ctx)
        // displayModelSpec 负责角色记忆 > ROLE_CHAT > apiConfig.currentModel 的完整回退
        reasoning = modelController.reasoning(displayModelSpec() ?: apiConfig.currentModel)
    }

    /** 从偏好或字号页面返回时刷新聊天页使用的设置快照。 */
    fun reloadUiSettings() {
        uiSettings = uiSettingsController.load()
    }

    fun selectModel(providerId: String, modelId: String) {
        val spec = "$providerId::$modelId"
        reasoning = modelController.select(currentCard?.name, spec)
        modelRevision++
    }

    fun updateReasoning(level: ReasoningLevel) {
        reasoning = level
        // 思考档位按当前实际生效的模型记忆，而非可能已过期的 apiConfig.currentModel
        modelController.saveReasoning(displayModelSpec() ?: apiConfig.currentModel, level)
    }

    /** 抽屉里可能改了用户名/头像，关抽屉时刷新 */
    fun reloadUserProfile() {
        userName = UserProfileStore.getName(ctx)
        viewModelScope.launch {
            userAvatarBitmap = withContext(Dispatchers.IO) { UserAvatarStore.load(ctx) }
        }
    }

    /** 从角色列表/编辑器返回时刷新当前卡：字段或图片可能已被编辑 */
    fun refreshCurrentCard() {
        val file = session?.charFile ?: return
        if (file.isBlank()) return
        val revision = invalidatePromptContext()
        viewModelScope.launch {
            applyPromptContext(PromptContextLoader.load(ctx, file), loadCharImage(file), revision)
        }
    }

    /** 从世界书/预设页返回或数据管理后刷新：关联内容与预设私有正则可能已被增删改 */
    fun reloadPromptData() {
        val revision = invalidatePromptContext()
        viewModelScope.launch {
            loadPromptData(revision)
        }
    }

    /** 按当前角色卡加载关联的世界书与预设；保留可用项并向 UI 上报损坏项。 */
    private suspend fun loadPromptData(revision: Long) {
        val file = session?.charFile.orEmpty()
        applyPromptContext(
            PromptContextLoader.load(ctx, file),
            if (file.isBlank()) null else loadCharImage(file),
            revision,
        )
    }

    private fun invalidatePromptContext(): Long {
        loadedPromptCharFile = null
        return ++promptContextRevision
    }

    private fun applyPromptContext(
        loaded: PromptContextLoader.Loaded,
        image: Bitmap?,
        revision: Long,
    ) {
        if (promptContextRevision != revision) return
        if (session?.charFile.orEmpty() != loaded.charFile) return
        currentCard = loaded.card
        activeWorldBooks = loaded.worldBooks
        activePreset = loaded.preset
        promptContextFailures = loaded.failures
        charImageBitmap = image
        loadedPromptCharFile = loaded.charFile
    }

    fun consumePromptContextFailures() {
        promptContextFailures = emptyList()
    }

    fun currentProviderAndModel(): Pair<ApiProvider, String>? =
        modelController.resolveProvider(currentCard?.name, apiConfig)

    // ── 会话管理 ──

    fun openSession(id: String) {
        if (generating) return
        // 悬浮窗是系统级、独立于会话：切换会话不停朗读
        viewModelScope.launch {
            sessionCoordinator.open(id)?.let { session = it }
        }
    }

    /** 顶栏"新聊天"：当前已是无用户消息的新聊天则不重复创建 */
    fun newChat() {
        val cur = session
        val alreadyNew = cur != null && cur.messages.none { it.role == "user" }
        if (generating || alreadyNew) return
        viewModelScope.launch {
            session = sessionCoordinator.create(
                card = currentCard,
                charFile = cur?.charFile ?: "",
                charName = cur?.charName ?: "",
                persist = true,
            )
        }
    }

    /** 角色选择面板：切到该角色最近的会话；没有则在内存里开新会话（发消息前不落盘）。
     *  偏好"切换角色时新建对话"开启时每次都新建，不再回到该角色的旧会话。 */
    fun openCharacter(fileName: String, displayName: String) {
        if (generating) return
        viewModelScope.launch {
            if (!uiSettings.newChatOnCharSwitch) {
                val existing = sessions.firstOrNull { it.charFile == fileName }
                if (existing != null) {
                    session = sessionCoordinator.open(existing.id) ?: existing
                    return@launch
                }
            }
            val card = if (fileName.isBlank()) null else loadCard(fileName)
            currentCard = card
            session = sessionCoordinator.create(card, fileName, displayName, persist = false)
        }
    }

    fun deleteSession(id: String) {
        if (!GenerationActionGuard.allowsMutation(generating)) return
        viewModelScope.launch {
            val replacement = sessionCoordinator.delete(
                id = id,
                currentSession = session,
                currentCard = currentCard,
                newChatOnDeleteTopic = uiSettings.newChatOnDeleteTopic,
            )
            if (session?.id == id) session = replacement
        }
    }

    fun renameSession(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            sessionCoordinator.rename(id, trimmed)
            if (session?.id == id) session = session?.copy(title = trimmed)
        }
    }

    fun setSessionPinned(id: String, pinned: Boolean) {
        viewModelScope.launch {
            sessionCoordinator.setPinned(id, pinned)
            if (session?.id == id) session = session?.copy(pinned = pinned)
        }
    }

    fun regenerateTitle(id: String) {
        viewModelScope.launch {
            ChatRepository.get(ctx, id)?.let { generateTitle(it, force = true) }
        }
    }

    /** 数据管理页可能清空了聊天记录/角色卡，返回时重新加载并校验当前会话 */
    fun refreshAfterDataManagement() {
        val revision = invalidatePromptContext()
        viewModelScope.launch {
            val fresh = ChatRepository.listSummaries(ctx)
            sessions = fresh
            val cur = session
            if (cur != null && fresh.any { it.id == cur.id }) {
                session = ChatRepository.get(ctx, cur.id)
            } else if (cur != null) {
                val card = if (cur.charFile.isBlank()) null else loadCard(cur.charFile)
                currentCard = card
                // 角色卡还在：留在该角色（开场白）；角色卡被清了：回到内置默认角色卡
                session = fresh.firstOrNull { it.charFile == cur.charFile }
                    ?.let { ChatRepository.get(ctx, it.id) }
                    ?: if (card != null) {
                        ConversationOps.newSession(card, cur.charFile, cur.charName)
                    } else {
                        val defName = CharacterRepository.defaultCardName(ctx)
                            ?: CharacterRepository.ensureDefaultCard(ctx, ctx.getString(R.string.default_character))
                        currentCard = loadCard(defName)
                        ConversationOps.newSession(currentCard, defName, defName)
                    }
            }
            // 世界书/预设（含预设私有正则）也可能被清空
            loadPromptData(revision)
        }
    }

    // ── 发送 / 重答 ──

    fun sendMessage(): SendOutcome {
        if (generating) return SendOutcome.SKIPPED
        if (loadedPromptCharFile != session?.charFile.orEmpty()) return SendOutcome.SKIPPED
        val text = inputText.trim()
        val atts = attachments
        if (text.isBlank() && atts.isEmpty()) return SendOutcome.SKIPPED
        // 编辑态：只更新该消息文本，不追加新消息、不触发生成
        val editTs = editingTs
        if (editTs != null) {
            if (text.isBlank()) return SendOutcome.SKIPPED
            updateMessage(editTs, text)
            editingTs = null
            inputText = ""
            return SendOutcome.STARTED
        }
        val (prov, modelId) = currentProviderAndModel() ?: return SendOutcome.NO_MODEL
        // 发送前同步校验文件大小：过大直接拦截（不清空附件，用户可移除或换文件）
        if (attachmentCoordinator.hasOversizedFile(atts)) {
            return SendOutcome.FILE_TOO_LARGE
        }
        inputText = ""
        attachments = emptyList()
        generationCoordinator.launch generationTask@{
            var originalSession: ChatSession? = null
            var createdNewSession = false
            try {
            // 附件先拷入自有目录（content URI 的读权限是临时的，落盘副本才能支撑历史展示与重答）。
            // 任一落盘失败（提供方不报大小、读取中途出错等）都中止发送并恢复输入与附件
            val persisted = attachmentCoordinator.persist(atts)
            if (persisted == null) {
                restoreDraftAfterFailedSend(text, atts)
                sendError = ctx.getString(R.string.attachment_send_failed)
                return@generationTask
            }
            // 以 DB 为准取当前会话（内存态可能落后于分支切换等 DB 变更）；未落盘的新会话回退内存态
            val existing = session?.id?.let { ChatRepository.get(ctx, it) }
            val src = existing ?: session ?: ChatSession()
            originalSession = src
            createdNewSession = existing == null
            val base = ConversationOps.appendUserMessage(
                src,
                text,
                persisted.images,
                persisted.files,
            )
            session = base
            val userMsg = base.messages.last()
            // 新用户消息即时进 overlay：putMessage 触发的分页 refresh 要过几帧才把它纳入 pagedBase，
            // 这期间渲染列表若少这一条，发送瞬间的贴底/滚动会按"少一条"的高度算，加载图标落不到真正底部。
            // 放进 overlay 后任何时刻列表都不缺它，分页补齐后由 settle 逻辑自动撤下。
            overlays = overlays + (userMsg.ts to userMsg)
            // 用户消息先落盘：生成中途 App 被杀/Activity 重建也不丢消息。
            // 已落盘会话只插新用户消息；未落盘的新会话整存一次（含开场白）
            if (existing != null) ChatRepository.putMessage(ctx, base.id, userMsg)
            else ChatRepository.save(ctx, base)
            runGeneration(base.id, prov, modelId, GenMode.SEND, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val before = originalSession
                if (before != null) {
                    val rollbackSucceeded = runCatching {
                        if (createdNewSession) ChatRepository.delete(ctx, before.id)
                        else ChatRepository.save(ctx, before)
                    }.onFailure { rollbackError ->
                        error.addSuppressed(rollbackError)
                        Log.e(CHAT_VIEW_MODEL_TAG, "发送失败后的会话回滚失败", rollbackError)
                    }.isSuccess
                    if (rollbackSucceeded) {
                        if (session?.id == before.id) session = before
                        val originalTimestamps = before.messages.mapTo(HashSet()) { it.ts }
                        overlays = overlays.filterKeys { it in originalTimestamps }
                        restoreDraftAfterFailedSend(text, atts)
                        sendError = ctx.getString(R.string.chat_send_failed_fmt, error.message.orEmpty())
                    } else {
                        runCatching { ChatRepository.get(ctx, before.id) }
                            .onSuccess { persisted ->
                                if (persisted != null && session?.id == before.id) session = persisted
                                if (persisted != null) {
                                    val persistedTimestamps = persisted.messages.mapTo(HashSet()) { it.ts }
                                    overlays = overlays.filterKeys { it in persistedTimestamps }
                                }
                            }
                            .onFailure { refreshError ->
                                error.addSuppressed(refreshError)
                                Log.e(CHAT_VIEW_MODEL_TAG, "回滚失败后刷新会话也失败", refreshError)
                            }
                        sendError = ctx.getString(
                            R.string.chat_send_rollback_failed_fmt,
                            error.message.orEmpty(),
                        )
                    }
                } else {
                    runCatching { attachmentCoordinator.collectUnused() }
                    restoreDraftAfterFailedSend(text, atts)
                    sendError = ctx.getString(R.string.chat_send_failed_fmt, error.message.orEmpty())
                }
                Log.e(CHAT_VIEW_MODEL_TAG, "发送消息失败", error)
            }
        }
        return SendOutcome.STARTED
    }

    /** 消费附件发送失败提示：UI 弹完 Toast 后清空，避免下次重组重复弹出 */
    fun consumeSendError() {
        sendError = null
    }

    private fun restoreDraftAfterFailedSend(text: String, sentAttachments: List<Attachment>) {
        if (text.isNotBlank()) {
            inputText = if (inputText.isBlank()) text else "$text\n$inputText"
        }
        val currentUris = attachments.mapTo(HashSet()) { it.uri }
        attachments = sentAttachments.filter { it.uri !in currentUris } + attachments
    }

    /** 进入编辑态：把该消息内容填入输入框，发送即更新该消息 */
    fun startEdit(ts: Long) {
        if (generating) return
        val msg = overlays[ts] ?: session?.messages?.firstOrNull { it.ts == ts } ?: return
        editingTs = ts
        inputText = msg.content
    }

    /** 取消编辑：退出编辑态并清空输入 */
    fun cancelEdit() {
        editingTs = null
        inputText = ""
    }

    fun stopGenerate() {
        generationCoordinator.stop()
    }

    /** 切换联网搜索开关（持久化，面板开关据此点亮/熄灭） */
    fun toggleSearch() {
        webSearchSettings = webSearchSettingsController.toggle(webSearchSettings)
    }

    fun toggleBuiltInSearch() {
        val (provider, modelId) = currentProviderAndModel() ?: return
        val modelIndex = provider.models.indexOfFirst { it.id == modelId }
        if (modelIndex < 0) return
        val model = provider.models[modelIndex]
        if (!model.supportsBuiltInTool(BuiltInTool.SEARCH, provider)) return
        val updated = model.copy(tools = if (BuiltInTool.SEARCH in model.tools) {
            model.tools - BuiltInTool.SEARCH
        } else {
            model.tools + BuiltInTool.SEARCH
        })
        val updatedProvider = provider.copy(models = provider.models.toMutableList().also { it[modelIndex] = updated })
        apiConfig = apiConfig.copy(providers = apiConfig.providers.map {
            if (it.id == provider.id) updatedProvider else it
        })
        ApiConfigStore.saveConfig(ctx, apiConfig)
        modelRevision++
    }

    /** 选择搜索服务商（面板卡片点击，按下标） */
    fun selectSearchProvider(index: Int) {
        webSearchSettings = webSearchSettingsController.select(webSearchSettings, index)
    }

    fun reloadSearchConfig() {
        webSearchSettings = webSearchSettingsController.load()
    }

    /** 朗读/停止朗读指定 AI 消息：同一消息再次点击即停止，换消息则打断旧朗读 */
    fun speakMessage(ts: Long) {
        val s = session ?: return
        val msg = overlays[ts] ?: s.messages.firstOrNull { it.ts == ts } ?: return
        ttsController.speak(ts, msg.content)
    }

    fun stopSpeaking() {
        ttsController.stop()
    }

    fun pauseTts() = ttsController.pause()
    fun resumeTts() = ttsController.resume()
    fun fastForwardTts() = ttsController.fastForward()

    /** 循环切换朗读速度：0.8x → 1.0x → 1.2x → 1.5x → 0.8x */
    fun cycleTtsSpeed() {
        ttsController.cycleSpeed()
    }

    /** 重新计算 UI 插槽类扩展的产出（快捷回复等）。扩展配置变更后调用（如从扩展设置返回）。 */
    fun refreshExtensionSlots() {
        val qr = mutableListOf<QuickReply>()
        for (ext in ExtensionStore.enabledExtensions(ctx)) {
            if (ext is QuickReplyProvider) qr += ext.quickReplies(ExtensionStore.getConfig(ctx, ext.info.id))
        }
        quickReplies = qr
    }

    /** 点击快捷回复：send=true 直接发送，否则插入输入框末尾。 */
    fun onQuickReply(qr: QuickReply): SendOutcome {
        return if (qr.send) {
            inputText = qr.text
            sendMessage()
        } else {
            inputText += qr.text
            SendOutcome.SKIPPED
        }
    }

    /** AI 消息重答：保留旧版本，新回复作为新版本（可左右切换）；其后的消息保留，由所有版本共享 */
    fun regenerateAi(ts: Long): SendOutcome {
        if (generating) return SendOutcome.SKIPPED
        if (loadedPromptCharFile != session?.charFile.orEmpty()) return SendOutcome.SKIPPED
        val s = session ?: return SendOutcome.SKIPPED
        val idx = s.messages.indexOfFirst { it.ts == ts }
        // 同步预判仅用于 SendOutcome（提示"先选模型"/是否滚动）；真正的目标计算在 runGeneration
        // 里以 DB 最新态重做，避免读到异步变更（删除/切换）后的陈旧 session 而复活/覆盖消息
        if (idx < 0 || s.messages[idx].role != "assistant") return SendOutcome.SKIPPED
        if (s.messages.take(idx).none { it.role == "user" }) return SendOutcome.SKIPPED  // 开场白不可重答
        val (prov, modelId) = currentProviderAndModel() ?: return SendOutcome.NO_MODEL
        startGenerate(s.id, prov, modelId, GenMode.REGEN, ts)
        return SendOutcome.STARTED
    }

    /** 用户消息重答：对其后的 AI 回复生成新版本 */
    fun regenerateAfterUser(ts: Long): SendOutcome {
        if (generating) return SendOutcome.SKIPPED
        if (loadedPromptCharFile != session?.charFile.orEmpty()) return SendOutcome.SKIPPED
        val s = session ?: return SendOutcome.SKIPPED
        val idx = s.messages.indexOfFirst { it.ts == ts }
        if (idx < 0) return SendOutcome.SKIPPED
        val next = s.messages.getOrNull(idx + 1)
        if (next?.role == "assistant") return regenerateAi(next.ts)
        val (prov, modelId) = currentProviderAndModel() ?: return SendOutcome.NO_MODEL
        // 走到这里说明其后没有 AI 回复（正常对话流总是有）：极罕见的用户消息连排场景，
        // 没有分支点可挂下文，直接截断到该用户消息后再生成（截断需先于生成完成，故同一协程内顺序执行）
        generationCoordinator.launch {
            ChatRepository.truncateAfter(ctx, s.id, ts)
            runGeneration(s.id, prov, modelId, GenMode.SEND, null)
        }
        return SendOutcome.STARTED
    }

    private enum class GenMode { SEND, REGEN }

    private fun startGenerate(sessionId: String, prov: ApiProvider, modelId: String, mode: GenMode, targetTs: Long?) {
        generationCoordinator.launch {
            runGeneration(sessionId, prov, modelId, mode, targetTs)
        }
    }

    /**
     * 一次生成的核心：以 DB 最新态重取会话，据 [mode] 组装目标消息（SEND 追加新 assistant；
     * REGEN 在 [targetTs] 上开新版本），流式内容走 overlay（不落盘空行），收尾只写一次最终消息。
     */
    private suspend fun runGeneration(
        sessionId: String, prov: ApiProvider, modelId: String, mode: GenMode, targetTs: Long?,
    ) {
        // 以 DB 最新态为准，避免异步变更后的陈旧内存态导致复活已删消息 / 覆盖已切分支
        val base = ChatRepository.get(ctx, sessionId) ?: return
        val genMessage: ChatMessage
        val buildHistory: List<ChatMessage>
        val promptHistory: List<ChatMessage>
        val updateTimed: Boolean
        if (mode == GenMode.REGEN) {
            val idx = base.messages.indexOfFirst { it.ts == targetTs }
            if (idx < 0 || base.messages[idx].role != "assistant") return
            if (base.messages.take(idx).none { it.role == "user" }) return
            genMessage = ConversationOps.startVariantOne(base.messages[idx], modelId)
            // 重答中间消息时世界书扫描只看该消息及之前的历史，与旧行为（截断后扫描）一致
            buildHistory = base.messages.subList(0, idx + 1)
            promptHistory = base.messages.subList(0, idx)
            updateTimed = false  // 重答历史消息不回写定时状态，避免污染 sticky/cooldown 窗口
        } else {
            genMessage = ChatMessage(role = "assistant", model = modelId, ts = ConversationOps.nextTs(base))
            buildHistory = base.messages
            promptHistory = base.messages
            updateTimed = true
        }
        session = base
        genTargetTs = genMessage.ts
        // 目标消息只进 overlay、不落盘空行：进程被杀不会残留空 assistant 行，也不需起始那次写库
        // （generating 已由调用方在挂起点之前同步置位，此处不再重复设置）
        overlays = overlays + (genMessage.ts to genMessage)
        // 联网搜索：开关开启时注册 search_web 函数工具，由模型思考后自行决定是否搜索、
        // 用什么关键词、搜几次（不再拿用户原话直搜）；模型内置搜索开启时不下发
        //（结果重复，且 Gemini 不允许内置搜索与函数工具并存）。搜索过程以时间线步骤
        // 展示（searching 状态只进 overlay），结果引用随消息落盘；失败回传错误给模型自行处理
        var msgForGen = if (mode == GenMode.REGEN) genMessage.copy(searches = emptyList()) else genMessage
        // 重答清掉旧引用后立即刷 overlay，避免生成期间残留上一版的引用胶囊
        if (mode == GenMode.REGEN) overlays = overlays + (genMessage.ts to msgForGen)
        // 内置搜索开启时只走模型侧搜索，App 不再注入外部搜索结果，避免两种搜索同时执行；
        // 仅开启 URL 上下文不影响 App 网络搜索（与搜索面板的显隐逻辑保持一致）。
        val builtInSearchOn = prov.model(modelId)?.tools?.contains(BuiltInTool.SEARCH) == true
        val webSearchOn = searchEnabled && !builtInSearchOn
        val commitVariables = mode == GenMode.SEND
        val assembledPrompt = promptAssembler.assemble(ChatPromptAssembler.Request(
            session = base,
            card = currentCard,
            userName = userName,
            worldBooks = activeWorldBooks,
            preset = activePreset,
            promptRegex = (currentCard?.regexScripts ?: emptyList()) + presetRegex,
            buildHistory = buildHistory,
            promptHistory = promptHistory,
            trimSummarizedHistory = mode == GenMode.SEND,
            updateTimed = updateTimed,
            reasoning = reasoning,
            modelId = modelId,
            generationMessage = genMessage,
            commitVariables = commitVariables,
        ))
        val built = assembledPrompt.built
        val apiMessages = assembledPrompt.apiMessages
        val variableState = assembledPrompt.variableState
        val localChanged = commitVariables && variableState.localChanged()
        val globalChanged = commitVariables && variableState.globalChanged()
        var localVariablesCommitted = false
        var globalVariablesCommitted = false
        try {
            if (localChanged) {
                ChatRepository.saveLocalVariables(ctx, sessionId, variableState.localVariables())
                localVariablesCommitted = true
            }
            if (globalChanged) {
                withContext(Dispatchers.IO) {
                    GlobalVariableStore.set(ctx, variableState.globalVariables())
                }
                globalVariablesCommitted = true
            }
            if (localChanged && session?.id == sessionId) {
                session = session?.copy(localVariables = variableState.localVariables())
            }
            val finalMsg = generation.run(
                apiMessages = apiMessages,
                genMessage = msgForGen,
                provider = prov,
                modelId = modelId,
                built = built,
                streaming = currentCard?.streaming ?: true,
                tools = if (webSearchOn) listOf(searchTool.spec()) else emptyList(),
                toolExecutor = if (webSearchOn) searchTool.executor() else null,
                errorText = { e -> ctx.getString(R.string.chat_error_fmt, e.message ?: "") },
                onUpdate = { overlays = overlays + (it.ts to it) },
            )
            // 最终消息与世界书定时状态在一个 Room 事务内提交。
            ChatRepository.commitGeneration(ctx, sessionId, finalMsg, built.timedWi)
            overlays = overlays + (finalMsg.ts to finalMsg)
            ChatRepository.get(ctx, sessionId)?.let { done ->
                if (session?.id == sessionId) session = done
                runExtensionLifecycle(done)
                maybeGenerateTitle(done)
            }
        } catch (error: Exception) {
            if (localVariablesCommitted) runCatching {
                ChatRepository.saveLocalVariables(ctx, sessionId, base.localVariables)
            }.onFailure { rollbackError ->
                error.addSuppressed(rollbackError)
                Log.w(CHAT_VIEW_MODEL_TAG, "会话变量回滚失败: $sessionId", rollbackError)
            }
            if (globalVariablesCommitted) withContext(Dispatchers.IO) {
                runCatching {
                    GlobalVariableStore.set(ctx, variableState.initialGlobalVariables())
                }.onFailure { rollbackError ->
                    error.addSuppressed(rollbackError)
                    Log.w(CHAT_VIEW_MODEL_TAG, "全局变量回滚失败", rollbackError)
                }
            }
            throw error
        }
    }

    /** 生成完成后跑扩展生命周期钩子（如摘要）：后台执行、失败隔离，完成后同步内存会话的扩展状态。 */
    private fun runExtensionLifecycle(done: ChatSession) {
        viewModelScope.launch {
            val services = HostServices(ctx, apiConfig)
            val cName = currentCard?.name ?: done.charName
            var ran = false
            for (ext in ExtensionStore.enabledExtensions(ctx)) {
                if (ext !is GenerationLifecycle) continue
                ran = true
                try {
                    ext.onGenerationComplete(
                        GenerationContext(
                            session = done,
                            charName = cName,
                            userName = userName,
                            extState = done.extState[ext.info.id] ?: "",
                            config = ExtensionStore.getConfig(ctx, ext.info.id),
                        ),
                        services,
                    )
                } catch (error: Exception) {
                    Log.w(CHAT_VIEW_MODEL_TAG, "扩展生成完成钩子失败: ${ext.info.id}", error)
                }
            }
            // 钩子可能写入了会话级状态（如摘要）：若仍停留在同一会话，刷新内存 extState 供下次拼装取用
            if (ran && session?.id == done.id) {
                ChatRepository.get(ctx, done.id)?.let { fresh ->
                    if (session?.id == done.id) session = session?.copy(extState = fresh.extState)
                }
            }
        }
    }

    /**
     * 首轮对话完成后自动生成会话标题：只要会话尚无标题、且至少有一轮完整的用户+AI 对答，
     * 就调用标题模型（标题角色配置，回退到当前聊天模型）生成简短标题。
     * 失败静默跳过（不清掉已有标题），空结果不写。
     */
    private fun maybeGenerateTitle(session: ChatSession) {
        generateTitle(session, force = false)
    }

    private fun generateTitle(session: ChatSession, force: Boolean) {
        viewModelScope.launch {
            try {
                val chatModel = displayModelSpec() ?: return@launch
                val title = titleGenerator.generate(
                    session = session,
                    force = force,
                    chatModel = chatModel,
                    apiConfig = apiConfig,
                    userName = userName,
                    charName = currentCard?.name ?: session.charName,
                ) ?: return@launch
                if (session.id == this@ChatViewModel.session?.id) {
                    this@ChatViewModel.session = this@ChatViewModel.session?.copy(title = title)
                }
            } catch (error: Exception) {
                Log.w(CHAT_VIEW_MODEL_TAG, "会话标题生成失败: ${session.id}", error)
            }
        }
    }

    // ── 消息操作（统一走 DB：按 ts 定位单条消息落盘，分页由 Room 自动刷新） ──
    //   写库异步、分页刷新有时间差，故变更先进 overlay 即时反映（滚动锚定/避免陈旧闪烁），
    //   DB 回来后把 overlay 校准到权威结果，最终由 UI 在分页补齐后 clearOverlay 撤下。

    /** 左右切换消息版本（DB 落盘 + 乐观 overlay 即时切换，供锚定同帧读到新内容） */
    fun switchAlt(ts: Long, dir: Int) {
        if (generating) return
        val s = session ?: return
        // 以当前显示态（未收敛的 overlay 优先，否则内存会话）为基准算新版本：连续快速切换才不丢中间态
        val cur = overlays[ts] ?: s.messages.firstOrNull { it.ts == ts } ?: return
        val optimistic = ConversationOps.switchAltOne(cur, dir) ?: return  // 到边界无切换：直接返回
        overlays = overlays + (ts to optimistic)
        viewModelScope.launch {
            messageCoordinator.switchAlt(s, ts, dir)?.let { fresh ->
                reconcileMessageMutation(s.id, ts, fresh)
            }
        }
    }

    /** 删除消息：多版本时只删当前显示的版本（下文不受影响），单版本删除整条 */
    fun deleteMessage(ts: Long) {
        if (generating) return
        val s = session ?: return
        // 删除是"移除行"，overlay 无法表示；撤掉该 ts 可能存在的 overlay，直接走 DB + 分页刷新
        overlays = overlays - ts
        viewModelScope.launch {
            messageCoordinator.deleteMessage(s, ts)?.let { fresh ->
                reconcileMessageMutation(s.id, ts, fresh)
            }
        }
    }

    /** 删除消息的全部版本（整条消息） */
    fun deleteAllVersions(ts: Long) {
        if (generating) return
        val s = session ?: return
        overlays = overlays - ts
        viewModelScope.launch {
            messageCoordinator.deleteAllVersions(s, ts)?.let { fresh ->
                reconcileMessageMutation(s.id, ts, fresh)
            }
        }
    }

    fun updateMessage(ts: Long, content: String) {
        val s = session ?: return
        val cur = overlays[ts] ?: s.messages.firstOrNull { it.ts == ts }
        if (cur != null) overlays = overlays + (ts to cur.copy(content = content))
        viewModelScope.launch {
            messageCoordinator.updateMessage(s, ts, content)?.let { fresh ->
                reconcileMessageMutation(s.id, ts, fresh)
            }
        }
    }

    /** UI 检测到分页已把该 ts 的最终内容补齐后调用：撤下顶替显示的 overlay */
    fun clearOverlay(ts: Long) {
        overlays = overlays - ts
    }

    /** 单条 DB 变更后把 overlay 校准到 DB 权威结果并同步内存会话（陈旧乐观值在此被纠正） */
    private fun reconcileMessageMutation(sid: String, ts: Long, fresh: ChatSession) {
        if (session?.id != sid) return
        session = fresh
        val row = fresh.messages.firstOrNull { it.ts == ts }
        overlays = if (row != null) overlays + (ts to row) else overlays - ts
    }

    fun updateUserProfile(name: String, description: String) {
        val trimmedName = name.trim().ifBlank { "user" }
        UserProfileStore.setName(ctx, trimmedName)
        UserProfileStore.setDescription(ctx, description)
        reloadUserProfile()
    }

    // ── IO 辅助 ──

    private suspend fun loadCard(file: String): CharacterCard? = withContext(Dispatchers.IO) {
        try {
            CharacterRepository.load(ctx, file)
        } catch (error: Exception) {
            Log.w(CHAT_VIEW_MODEL_TAG, "角色卡加载失败: $file", error)
            null
        }
    }

    private suspend fun loadCharImage(file: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = CharacterRepository.imageFile(ctx, file)
        if (!f.exists()) return@withContext null
        try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (error: Exception) {
            Log.w(CHAT_VIEW_MODEL_TAG, "角色图片加载失败: $file", error)
            null
        }
    }

}
