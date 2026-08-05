package me.rerere.fawntavern.ui.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiConfigStore
import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.chat.AttachmentStore
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatRepository
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.chat.MsgFile
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.preset.StPreset
import me.rerere.fawntavern.data.preset.toCharRegex
import me.rerere.fawntavern.data.settings.UserProfileStore
import me.rerere.fawntavern.data.settings.PromptLogStore
import me.rerere.fawntavern.data.settings.CharacterModelStore
import me.rerere.fawntavern.data.settings.DefaultModelStore
import me.rerere.fawntavern.data.settings.ThinkingStore
import me.rerere.fawntavern.data.settings.WorldInfoSettingsStore
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.data.worldbook.WorldBookRepository
import me.rerere.fawntavern.domain.ConversationOps
import me.rerere.fawntavern.domain.GenerationController
import me.rerere.fawntavern.domain.PromptBuilder
import me.rerere.fawntavern.domain.PromptLog
import me.rerere.fawntavern.extension.BuiltinExtensions
import me.rerere.fawntavern.extension.ExtensionStore
import me.rerere.fawntavern.extension.GenerationContext
import me.rerere.fawntavern.extension.GenerationLifecycle
import me.rerere.fawntavern.extension.HostServices
import me.rerere.fawntavern.extension.PromptContext
import me.rerere.fawntavern.extension.PromptContributor
import me.rerere.fawntavern.extension.QuickReply
import me.rerere.fawntavern.extension.QuickReplyProvider

/**
 * 聊天状态容器：只负责持有 UI 状态、调度协程和落盘。
 * 业务逻辑在 domain 层：Prompt 拼装 → [PromptBuilder]，
 * 会话/消息纯变换 → [ConversationOps]，流式生成 → [GenerationController]。
 * 生成协程运行在 viewModelScope —— Activity 因深色模式/语言切换等重建时不中断，状态不丢失。
 * UI（ChatScreen）只读状态、调方法，不直接改。
 */
internal class ChatViewModel(app: Application) : AndroidViewModel(app) {

    /** 发送/重答的结果：UI 据此决定是否弹"先选模型"提示 */
    enum class SendOutcome { STARTED, NO_MODEL, SKIPPED }

    // ── 状态（写入只经由本类方法） ──
    var apiConfig by mutableStateOf(ApiConfigStore.loadConfig(app)); private set
    /** 当前模型的思考预算档位（按模型记忆，随选模型切换）；AUTO = 不下发任何思考字段 */
    var reasoning by mutableStateOf(ThinkingStore.get(app, apiConfig.currentModel)); private set
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
    /** 当前/最近一次生成的目标消息 ts（重答时指向被重答的消息），null = 尚未生成过 */
    var genTargetTs by mutableStateOf<Long?>(null); private set
    var userName by mutableStateOf(UserProfileStore.getName(app)); private set
    var userAvatarBitmap by mutableStateOf<Bitmap?>(null); private set
    // 当前角色关联的世界书/预设（随角色卡切换加载，生成时进入 Prompt 拼装）
    var activeWorldBooks by mutableStateOf<List<WorldBook>>(emptyList()); private set
    var activePreset by mutableStateOf<StPreset?>(null); private set
    // 输入草稿放这里，Activity 重建后不丢
    var inputText by mutableStateOf("")
    var attachments by mutableStateOf(listOf<Attachment>())

    /** 启用的快捷回复（UI 插槽扩展提供），随扩展配置刷新 */
    var quickReplies by mutableStateOf<List<QuickReply>>(emptyList()); private set

    /** 当前预设私有的正则（关联该预设的聊天才生效），转成引擎统一类型 */
    private val presetRegex: List<CharRegex>
        get() = activePreset?.regexScripts?.map { it.toCharRegex() } ?: emptyList()

    /** 显示侧正则：角色卡内嵌 + 当前预设私有 */
    val displayRegexScripts: List<CharRegex>
        get() = (currentCard?.regexScripts ?: emptyList()) + presetRegex

    private val generation = GenerationController()
    private val ctx: Application get() = getApplication()

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
            if (ChatRepository.list(ctx).isEmpty()) {
                session = ConversationOps.newSession(loadCard(defaultName), defaultName, defaultName, userName)
            }
            ChatRepository.sessionsFlow(ctx).collect {
                sessions = it
                if (session == null) session = it.firstOrNull()
            }
        }
        // 会话的角色变化时加载对应角色卡，并恢复该角色记忆的模型
        viewModelScope.launch {
            snapshotFlow { session?.charFile }.collect { file ->
                currentCard = if (file.isNullOrBlank()) null else loadCard(file)
                charImageBitmap = if (file.isNullOrBlank()) null else loadCharImage(file)
                loadPromptData()
                // 恢复该角色的模型：角色记忆 > 默认模型聊天卡片
                val name = currentCard?.name
                val charModel = if (!name.isNullOrBlank()) CharacterModelStore.get(ctx, name).takeIf { it.isNotBlank() } else null
                val defaultChat = DefaultModelStore.get(ctx, DefaultModelStore.ROLE_CHAT).model.takeIf { it.isNotBlank() }
                val spec = charModel ?: defaultChat ?: ""
                reasoning = ThinkingStore.get(ctx, spec)
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

    // ── 配置 / 用户资料 ──

    /** 当前页面的模型：角色记忆 > ROLE_CHAT > apiConfig.currentModel 回退，全空时返回 null */
    fun displayModelSpec(): String? {
        val charModel = currentCard?.name?.let { CharacterModelStore.get(ctx, it) }.takeIf { !it.isNullOrBlank() }
        if (charModel != null) return charModel
        val defaultChat = DefaultModelStore.get(ctx, DefaultModelStore.ROLE_CHAT).model.takeIf { it.isNotBlank() }
        if (defaultChat != null) return defaultChat
        return apiConfig.currentModel.takeIf { it.isNotBlank() }
    }

    /** 从 API 配置页返回时刷新：若模型仍在则保持，若模型被删除/禁用则切到全局配置的 currentModel 兜底 */
    fun reloadApiConfig() {
        apiConfig = ApiConfigStore.loadConfig(ctx)
        // displayModelSpec 负责角色记忆 > ROLE_CHAT > apiConfig.currentModel 的完整回退
        reasoning = ThinkingStore.get(ctx, displayModelSpec() ?: apiConfig.currentModel)
    }

    fun selectModel(providerId: String, modelId: String) {
        val spec = "$providerId::$modelId"
        val name = currentCard?.name
        if (!name.isNullOrBlank()) {
            CharacterModelStore.set(ctx, name, spec)
        } else {
            // 无角色卡时记到全局聊天默认模型，避免选模型后无法持久化
            DefaultModelStore.setModel(ctx, DefaultModelStore.ROLE_CHAT, spec)
        }
        reasoning = ThinkingStore.get(ctx, spec)
    }

    fun updateReasoning(level: ReasoningLevel) {
        reasoning = level
        // 思考档位按当前实际生效的模型记忆，而非可能已过期的 apiConfig.currentModel
        ThinkingStore.set(ctx, displayModelSpec() ?: apiConfig.currentModel, level)
    }

    /** 抽屉里可能改了用户名/头像，关抽屉时刷新 */
    fun reloadUserProfile() {
        userName = UserProfileStore.getName(ctx)
        viewModelScope.launch {
            userAvatarBitmap = withContext(Dispatchers.IO) { loadAvatarBitmap() }
        }
    }

    /** 从角色列表/编辑器返回时刷新当前卡：字段或图片可能已被编辑 */
    fun refreshCurrentCard() {
        val file = session?.charFile ?: return
        if (file.isBlank()) return
        viewModelScope.launch {
            currentCard = loadCard(file)
            charImageBitmap = loadCharImage(file)
            loadPromptData()
        }
    }

    /** 从世界书/预设页返回或数据管理后刷新：关联内容与预设私有正则可能已被增删改 */
    fun reloadPromptData() {
        viewModelScope.launch {
            loadPromptData()
        }
    }

    /** 按当前角色卡加载关联的世界书与预设（缺失/损坏的静默跳过） */
    private suspend fun loadPromptData() {
        val card = currentCard
        activeWorldBooks = if (card == null) emptyList() else {
            (card.enabledWorldBooks + card.world).filter { it.isNotBlank() }.distinct()
                .mapNotNull { name -> try { WorldBookRepository.load(ctx, name) } catch (_: Exception) { null } }
        }
        activePreset = card?.linkedPreset?.takeIf { it.isNotBlank() }
            ?.let { name -> try { PresetRepository.load(ctx, name) } catch (_: Exception) { null } }
    }

    fun currentProviderAndModel(): Pair<ApiProvider, String>? {
        // 角色记忆 > ROLE_CHAT > apiConfig.currentModel 回退；全空则须显式选择模型
        val charModel = currentCard?.name?.let { CharacterModelStore.get(ctx, it) }.takeIf { !it.isNullOrBlank() }
        val defaultChat = DefaultModelStore.get(ctx, DefaultModelStore.ROLE_CHAT).model.takeIf { it.isNotBlank() }
        val spec = charModel ?: defaultChat ?: apiConfig.currentModel.takeIf { it.isNotBlank() } ?: return null
        val prov = apiConfig.providers.find {
            it.id == spec.substringBefore("::") && it.enabled
        }
        val modelId = spec.substringAfter("::", "")
        if (prov == null || modelId.isBlank()) return null
        return prov to modelId
    }

    // ── 会话管理 ──

    fun openSession(id: String) {
        if (generating) return
        session = sessions.firstOrNull { it.id == id } ?: session
    }

    /** 顶栏"新聊天"：当前已是无用户消息的新聊天则不重复创建 */
    fun newChat() {
        val cur = session
        val alreadyNew = cur != null && cur.messages.none { it.role == "user" }
        if (generating || alreadyNew) return
        viewModelScope.launch {
            val s = ConversationOps.newSession(currentCard, cur?.charFile ?: "", cur?.charName ?: "", userName)
            session = s
            ChatRepository.save(ctx, s)
        }
    }

    /** 角色选择面板：切到该角色最近的会话；没有则在内存里开新会话（发消息前不落盘） */
    fun openCharacter(fileName: String, displayName: String) {
        if (generating) return
        viewModelScope.launch {
            val existing = sessions.firstOrNull { it.charFile == fileName }
            if (existing != null) {
                session = existing
                return@launch
            }
            val card = if (fileName.isBlank()) null else loadCard(fileName)
            currentCard = card
            session = ConversationOps.newSession(card, fileName, displayName, userName)
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            val curCharFile = session?.charFile ?: ""
            val curCharName = session?.charName ?: ""
            ChatRepository.delete(ctx, id)
            val fresh = ChatRepository.list(ctx)
            if (session?.id == id) {
                // 留在当前角色：优先切到该角色的其他会话；没有则只在内存里展示
                // 一个新会话（有角色卡显示开场白，否则为空），不落盘
                session = fresh.firstOrNull { it.charFile == curCharFile }
                    ?: ConversationOps.newSession(currentCard, curCharFile, curCharName, userName)
            }
        }
    }

    /** 数据管理页可能清空了聊天记录/角色卡，返回时重新加载并校验当前会话 */
    fun refreshAfterDataManagement() {
        viewModelScope.launch {
            val fresh = ChatRepository.list(ctx)
            sessions = fresh
            val cur = session
            if (cur != null && fresh.none { it.id == cur.id }) {
                val card = if (cur.charFile.isBlank()) null else loadCard(cur.charFile)
                currentCard = card
                // 角色卡还在：留在该角色（开场白）；角色卡被清了：回到内置默认角色卡
                session = fresh.firstOrNull { it.charFile == cur.charFile }
                    ?: if (card != null) {
                        ConversationOps.newSession(card, cur.charFile, cur.charName, userName)
                    } else {
                        val defName = CharacterRepository.defaultCardName(ctx)
                            ?: CharacterRepository.ensureDefaultCard(ctx, ctx.getString(R.string.default_character))
                        currentCard = loadCard(defName)
                        ConversationOps.newSession(currentCard, defName, defName, userName)
                    }
            }
            // 世界书/预设（含预设私有正则）也可能被清空
            loadPromptData()
        }
    }

    // ── 发送 / 重答 ──

    fun sendMessage(): SendOutcome {
        if (generating) return SendOutcome.SKIPPED
        val text = inputText.trim()
        val atts = attachments
        if (text.isBlank() && atts.isEmpty()) return SendOutcome.SKIPPED
        val (prov, modelId) = currentProviderAndModel() ?: return SendOutcome.NO_MODEL
        inputText = ""
        attachments = emptyList()
        generating = true  // 同步置位:附件落盘/DB 读等挂起点之前就挡住重复发送与并发生成
        viewModelScope.launch {
            try {
                // 附件先拷入自有目录（content URI 的读权限是临时的，落盘副本才能支撑历史展示与重答）
                val images = mutableListOf<String>()
                val files = mutableListOf<MsgFile>()
                for (a in atts) {
                    if (a.isImage) AttachmentStore.persistImage(ctx, a.uri)?.let { images.add(it) }
                    else AttachmentStore.persistFile(ctx, a.uri)?.let { files.add(it) }
                }
                // 以 DB 为准取当前会话（内存态可能落后于分支切换等 DB 变更）；未落盘的新会话回退内存态
                val existing = session?.id?.let { ChatRepository.get(ctx, it) }
                val src = existing ?: session ?: ChatSession()
                val base = ConversationOps.appendUserMessage(src, text, images, files)
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
            } finally {
                generating = false
            }
        }
        return SendOutcome.STARTED
    }

    fun stopGenerate() {
        generation.stop()
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
        val s = session ?: return SendOutcome.SKIPPED
        val idx = s.messages.indexOfFirst { it.ts == ts }
        if (idx < 0) return SendOutcome.SKIPPED
        val next = s.messages.getOrNull(idx + 1)
        if (next?.role == "assistant") return regenerateAi(next.ts)
        val (prov, modelId) = currentProviderAndModel() ?: return SendOutcome.NO_MODEL
        // 走到这里说明其后没有 AI 回复（正常对话流总是有）：极罕见的用户消息连排场景，
        // 没有分支点可挂下文，直接截断到该用户消息后再生成（截断需先于生成完成，故同一协程内顺序执行）
        generating = true  // 同步置位:截断/DB 读挂起点之前就挡住并发
        viewModelScope.launch {
            try {
                ChatRepository.truncateAfter(ctx, s.id, ts)
                runGeneration(s.id, prov, modelId, GenMode.SEND, null)
            } finally {
                generating = false
            }
        }
        return SendOutcome.STARTED
    }

    private enum class GenMode { SEND, REGEN }

    private fun startGenerate(sessionId: String, prov: ApiProvider, modelId: String, mode: GenMode, targetTs: Long?) {
        generating = true  // 同步置位:runGeneration 内首个 get() 挂起前就挡住重复触发（防双击并发生成）
        viewModelScope.launch {
            try {
                runGeneration(sessionId, prov, modelId, mode, targetTs)
            } finally {
                generating = false
            }
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
        // 收集启用扩展的提示贡献（如摘要），并入历史之外的固定块（不进历史流、不被预算裁剪）
        val extraPre = mutableListOf<PromptBuilder.Piece>()
        val extraPost = mutableListOf<PromptBuilder.Piece>()
        val extraDepth = mutableListOf<PromptBuilder.DepthPiece>()
        var historySkip = 0
        for (ext in ExtensionStore.enabledExtensions(ctx)) {
            if (ext !is PromptContributor) continue
            val c = ext.contribute(
                PromptContext(
                    session = base,
                    charName = currentCard?.name ?: base.charName,
                    userName = userName,
                    extState = base.extState[ext.info.id] ?: "",
                    config = ExtensionStore.getConfig(ctx, ext.info.id),
                )
            )
            val srcTag = PromptBuilder.PromptSource.EXTENSION
            extraPre += c.preHistory.map { PromptBuilder.Piece(it.role, it.content, srcTag, ext.info.name) }
            extraPost += c.postHistory.map { PromptBuilder.Piece(it.role, it.content, srcTag, ext.info.name) }
            extraDepth += c.depthInjections.map { PromptBuilder.DepthPiece(it.role, it.content, it.depth, srcTag, ext.info.name) }
            if (c.skipMessagesUpTo > historySkip) historySkip = c.skipMessagesUpTo
        }
        // 扩展声明了要跳过的历史消息（如摘要已压缩前 N 条）→ 裁剪 buildHistory 和 promptHistory，
        // 用扩展注入的固定块（preHistory 中的摘要）代替原文。仅 SEND 模式裁剪（REGEN 重答中间消息
        // 时前面的上下文不可省略）；跳过数不得超过实际消息数
        val slicedBuildHistory = if (mode == GenMode.SEND && historySkip > 0 && historySkip < buildHistory.size)
            buildHistory.drop(historySkip) else buildHistory
        val slicedPromptHistory = if (mode == GenMode.SEND && historySkip > 0 && historySkip < promptHistory.size)
            promptHistory.drop(historySkip) else promptHistory
        val built = PromptBuilder.build(
            card = currentCard,
            userName = userName,
            userDescription = UserProfileStore.getDescription(ctx),
            worldBooks = activeWorldBooks,
            preset = activePreset,
            history = slicedBuildHistory,
            promptRegex = (currentCard?.regexScripts ?: emptyList()) + presetRegex,
            timedWi = base.timedWi,
            updateTimed = updateTimed,
            wiSettings = WorldInfoSettingsStore.get(ctx),
            reasoning = reasoning,
            extraPre = extraPre,
            extraPost = extraPost,
            extraDepth = extraDepth,
        )
        val finalMsg = generation.run(
            promptHistory = slicedPromptHistory,
            genMessage = genMessage,
            provider = prov,
            modelId = modelId,
            built = built,
            filesDir = ctx.filesDir,
            streaming = currentCard?.streaming ?: true,
            errorText = { e -> ctx.getString(R.string.chat_error_fmt, e.message ?: "") },
            onUpdate = { overlays = overlays + (it.ts to it) },
        )
        // 收尾：最终消息与会话定时状态落盘。overlay 不在此清除——留着顶替显示，等分页把最终内容
        // 补齐后由 UI（clearOverlay）撤下，避免"撤 overlay 时分页还没刷新出最终内容"的空帧
        ChatRepository.putMessage(ctx, sessionId, finalMsg)
        ChatRepository.saveTimedWi(ctx, sessionId, built.timedWi)
        overlays = overlays + (finalMsg.ts to finalMsg)  // 定格最终内容（供 UI 比对分页是否补齐）
        // generating 由调用方 finally 复位（覆盖异常/提前 return 各路径）
        // 同步内存会话（供下次生成拼装历史 + 菜单/编辑按 ts 取消息）；仍停留在本会话才覆盖。
        // 生命周期钩子（如摘要）无论是否切走都对已完成会话执行
        ChatRepository.get(ctx, sessionId)?.let { done ->
            if (session?.id == sessionId) session = done
            runExtensionLifecycle(done)
            maybeGenerateTitle(done)
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
                } catch (_: Exception) { /* 单个扩展失败不影响其它 */ }
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
     * 就调用标题模型（DefaultModelStore.ROLE_TITLE，回退到当前聊天模型）生成简短标题。
     * 失败静默跳过（不清掉已有标题），空结果不写。
     */
    private fun maybeGenerateTitle(session: ChatSession) {
        if (session.title.isNotBlank()) return
        val userMsgs = session.messages.filter { it.role == "user" }
        val aiMsgs = session.messages.filter { it.role == "assistant" }
        if (userMsgs.isEmpty() || aiMsgs.isEmpty()) return
        viewModelScope.launch {
            try {
                val chatModel = displayModelSpec() ?: return@launch
                val resolved = DefaultModelStore.resolveModel(ctx, DefaultModelStore.ROLE_TITLE, chatModel)
                    ?: return@launch
                val (provId, modelId) = resolved
                val prov = apiConfig.providers.find { it.id == provId && it.enabled } ?: return@launch
                // 取前 2 轮对答摘要作为标题上下文
                val historyLines = mutableListOf<String>()
                val cName = currentCard?.name ?: session.charName
                val maxPairs = minOf(userMsgs.size, aiMsgs.size, 2)
                for (i in 0 until maxPairs) {
                    historyLines.add("${userName}: ${userMsgs[i].content.take(200)}")
                    historyLines.add("$cName: ${aiMsgs[i].content.take(200)}")
                }
                val historyPreview = historyLines.joinToString("\n")

                val promptEntry = DefaultModelStore.get(ctx, DefaultModelStore.ROLE_TITLE)
                val titlePrompt = if (promptEntry.prompt.isNotBlank()) {
                    promptEntry.prompt.replace("{content}", historyPreview)
                } else {
                    DefaultModelStore.DEFAULT_TITLE_PROMPT.replace("{content}", historyPreview)
                }

                val services = HostServices(ctx, apiConfig)
                val title = services.callModel(
                    messages = listOf(
                        ApiMessage("user", titlePrompt),
                    ),
                    params = null,
                    modelId = "$provId::$modelId",
                ).trim().take(80)

                if (title.isNotBlank()) {
                    ChatRepository.updateTitle(ctx, session.id, title)
                    if (session.id == this@ChatViewModel.session?.id) {
                        this@ChatViewModel.session = this@ChatViewModel.session?.copy(title = title)
                    }
                }
            } catch (_: Exception) { /* 标题生成失败静默跳过 */ }
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
            ensurePersisted(s)
            ChatRepository.switchAlt(ctx, s.id, ts, dir)
            reconcileOverlay(s.id, ts)
        }
    }

    /** 删除消息：多版本时只删当前显示的版本（下文不受影响），单版本删除整条 */
    fun deleteMessage(ts: Long) {
        if (generating) return
        val s = session ?: return
        // 删除是"移除行"，overlay 无法表示；撤掉该 ts 可能存在的 overlay，直接走 DB + 分页刷新
        overlays = overlays - ts
        viewModelScope.launch {
            ensurePersisted(s)
            ChatRepository.deleteMessage(ctx, s.id, ts)
            resyncSession(s.id)
        }
    }

    fun updateMessage(ts: Long, content: String) {
        val s = session ?: return
        val cur = overlays[ts] ?: s.messages.firstOrNull { it.ts == ts }
        if (cur != null) overlays = overlays + (ts to cur.copy(content = content))
        viewModelScope.launch {
            ensurePersisted(s)
            ChatRepository.editMessage(ctx, s.id, ts, content)
            reconcileOverlay(s.id, ts)
        }
    }

    /** UI 检测到分页已把该 ts 的最终内容补齐后调用：撤下顶替显示的 overlay */
    fun clearOverlay(ts: Long) {
        overlays = overlays - ts
    }

    /** 单条 DB 变更后把 overlay 校准到 DB 权威结果并同步内存会话（陈旧乐观值在此被纠正） */
    private suspend fun reconcileOverlay(sid: String, ts: Long) {
        val fresh = ChatRepository.get(ctx, sid) ?: return
        if (session?.id != sid) return
        session = fresh
        val row = fresh.messages.firstOrNull { it.ts == ts }
        overlays = if (row != null) overlays + (ts to row) else overlays - ts
    }

    /**
     * 首次修改前把仅存在于内存的会话（如只有开场白、尚未发消息的新会话）整存落盘，
     * 使其消息进入 DB / 分页，随后的按 ts 单条操作才有行可改。仅浏览角色不触发落盘。
     */
    private suspend fun ensurePersisted(s: ChatSession) {
        if (ChatRepository.get(ctx, s.id) == null) ChatRepository.save(ctx, s)
    }

    /** 单条消息落盘后把内存会话同步回 DB（供下次生成拼装历史 + 菜单/编辑按 ts 取消息） */
    private suspend fun resyncSession(sid: String) {
        ChatRepository.get(ctx, sid)?.let { fresh -> if (session?.id == sid) session = fresh }
    }

    // ── IO 辅助 ──

    private suspend fun loadCard(file: String): CharacterCard? = withContext(Dispatchers.IO) {
        try { CharacterRepository.load(ctx, file) } catch (_: Exception) { null }
    }

    private suspend fun loadCharImage(file: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = CharacterRepository.imageFile(ctx, file)
        if (!f.exists()) return@withContext null
        try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null }
    }

    private fun loadAvatarBitmap(): Bitmap? {
        val path = UserProfileStore.getAvatarPath(ctx) ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
    }
}
