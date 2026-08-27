package me.rerere.fawntavern.ui.chat

import android.app.Application
import me.rerere.fawntavern.core.diagnostics.SafeLog
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.BuiltInTool
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.api.Modality
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.api.supportsBuiltInTool
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.domain.GenerationController
import me.rerere.fawntavern.domain.GenerationActionGuard
import me.rerere.fawntavern.domain.PromptBuilder
import me.rerere.fawntavern.domain.PromptLog
import me.rerere.fawntavern.extension.BuiltinExtensions
import me.rerere.fawntavern.extension.QuickReply
import me.rerere.fawntavern.extension.QuickReplyProvider

private const val CHAT_VIEW_MODEL_TAG = "ChatViewModel"

/**
 * 聊天状态容器：组合 UI 状态持有者并把界面动作调度到用例/协调器。
 * 业务逻辑在 domain 层：Prompt 拼装 → [PromptBuilder]，
 * 会话/消息变换由状态持有者与用例处理，流式生成由 [GenerationController] 处理。
 * 生成协程运行在 viewModelScope —— Activity 因深色模式/语言切换等重建时不中断，状态不丢失。
 * UI（ChatScreen）只读取 [uiState]、发送 [ChatAction]、处理 [ChatEffect]。
 */
internal class ChatViewModel(
    app: Application,
    private val dependencies: ChatFeatureDependencies,
) : AndroidViewModel(app) {

    /** Internal result mapped to one-shot [ChatEffect] values by [dispatch]. */
    private enum class SendOutcome { STARTED, NO_MODEL, SKIPPED, FILE_TOO_LARGE }

    private val effectChannel = Channel<ChatEffect>(Channel.BUFFERED)
    val effects: Flow<ChatEffect> = effectChannel.receiveAsFlow()

    // ── 状态（写入只经由本类方法） ──
    private val model = ChatModelStateHolder(
        ChatModelController(dependencies.modelDataSource),
        dependencies.apiConfigRepository,
    )
    private val apiConfig get() = model.apiConfig
    private val uiSettings = ChatUiSettingsStateHolder(
        ChatUiSettingsController(dependencies.uiSettingsDataSource),
    )
    /** 当前模型的思考预算档位（按模型记忆，随选模型切换）；AUTO = 不下发任何思考字段 */
    private val reasoning get() = model.reasoning
    /** 当前模型的图片生成控制项（按模型记忆）。 */
    private val imageGeneration get() = model.imageGeneration
    private val conversation = ChatConversationStateHolder()
    private val sessions get() = conversation.sessions
    private val session get() = conversation.current
    private val promptContext = ChatPromptContextStateHolder()
    private val promptContextDataSource = dependencies.promptContextDataSource
    private val currentCard get() = promptContext.card
    /**
     * 覆盖在分页列表之上的内存消息（按 ts 索引）：承载流式生成的实时内容、以及分支切换/编辑的
     * 乐观即时反馈。写库是异步的（DB→Room 失效→分页重刷有几帧时间差），overlay 在此期间顶替显示，
     * 待分页把该 ts 的最终内容补齐后由 UI 调 [clearOverlay] 撤下——避免空帧/陈旧内容闪烁，
     * 并让滚动锚定像旧同步逻辑一样在下一帧就能读到新内容。
     */
    private val overlays get() = conversation.overlays
    private val profile = ChatProfileStateHolder(
        ChatUserProfileController(dependencies.userProfileDataSource),
    )
    private val userName get() = profile.name
    private val input = ChatInputStateHolder()
    val inputState get() = input.textFieldState

    private val search = ChatSearchStateHolder(
        ChatWebSearchSettingsController(dependencies.searchSettingsDataSource),
    )
    private val searchEnabled: Boolean
        get() = search.enabled
    private val searchProviderIndex: Int
        get() = search.providerIndex
    private val searchServices
        get() = search.services
    private val searchProviderName: String
        get() = search.providerName
    private val builtInSearchAvailable: Boolean
        get() {
            model.revision
            val (provider, modelId) = currentProviderAndModel() ?: return false
            return provider.model(modelId)?.supportsBuiltInTool(BuiltInTool.SEARCH, provider) == true
        }
    private val builtInSearchEnabled: Boolean
        get() {
            model.revision
            val (provider, modelId) = currentProviderAndModel() ?: return false
            return BuiltInTool.SEARCH in (provider.model(modelId)?.tools ?: emptySet())
        }
    private val imageGenerationAvailable: Boolean
        get() {
            model.revision
            val (provider, modelId) = currentProviderAndModel() ?: return false
            return Modality.IMAGE in (provider.model(modelId)?.outputModalities ?: emptyList())
        }
    private val tts = ChatTtsStateHolder(app, viewModelScope)

    private val displayRegexScripts: List<CharRegex>
        get() = promptContext.displayRegex

    private val chatRepository = dependencies.chatRepository
    private val generation = GenerationController(dependencies.generationGateway)
    private val ctx: Application get() = getApplication()
    private val generationCoordinator by lazy {
        ChatGenerationCoordinator(
            scope = viewModelScope,
            stopCurrent = generation::stop,
            onFailure = { error ->
                SafeLog.error(CHAT_VIEW_MODEL_TAG, "generation_failed", error)
                showMessage(ctx.getString(R.string.chat_generation_failed_fmt, error.message.orEmpty()))
            },
        )
    }
    private val generating: Boolean
        get() = generationCoordinator.isRunning
    private val messageCoordinator by lazy { ChatMessageCoordinator(chatRepository) }
    private val attachmentCoordinator by lazy {
        ChatAttachmentCoordinator(dependencies.attachmentDataSource)
    }
    private val sendChatMessage by lazy {
        SendChatMessageUseCase(chatRepository, attachmentCoordinator)
    }
    private val refreshChatData by lazy {
        RefreshChatDataUseCase(chatRepository, promptContextDataSource)
    }
    private val sessionCoordinator by lazy {
        ChatSessionCoordinator(RepositoryChatSessionDataSource(chatRepository))
    }
    private val generationRunner by lazy {
        val promptAssembler = ChatPromptAssembler(dependencies.promptEnvironment)
        ChatGenerationRunner(
            chatRepository = chatRepository,
            generation = generation,
            resources = dependencies.generationResources,
            prepare = PrepareChatGenerationUseCase(chatRepository, promptAssembler),
            commit = CommitChatGenerationUseCase(chatRepository),
            searchTool = ChatSearchTool(dependencies.searchToolDataSource),
        )
    }
    private val postGenerationCoordinator by lazy {
        ChatPostGenerationCoordinator(
            ctx,
            viewModelScope,
            chatRepository,
            dependencies.extensionGateway,
        )
    }

    /**
     * 当前会话消息的分页流（Paging 3）。随 [session] 的 id 切换：初始加载偏移定位到最后一页，
     * 天然停在底部；后续任何 DB 写入由 Room 使数据源失效自动重刷。未落盘的新会话（仅开场白）
     * 分页为空，UI 回退到 [session] 内存消息显示开场白。
     */
    val pagedMessages: Flow<PagingData<ChatMessage>> = chatPagingSource(
        repository = chatRepository,
        sessionIds = snapshotFlow { session?.id },
    ).cachedIn(viewModelScope)

    init {
        // 登记内置官方扩展（幂等）
        BuiltinExtensions.registerAll()
        refreshExtensionSlots()
        // Prompt 调试日志开关：把持久化设置同步到内存 sink（关闭时生成不记录）
        PromptLog.enabled = dependencies.promptLogEnabled
        // 会话列表来自 Repository 的 Flow：任何 save/delete/clear 后自动刷新
        viewModelScope.launch {
            val defaultName = promptContextDataSource.ensureDefaultCharacter(
                ctx.getString(R.string.default_preset),
                ctx.getString(R.string.default_character),
            )
            if (sessionCoordinator.count() == 0) {
                conversation.setCurrent(
                    sessionCoordinator.create(
                        loadCard(defaultName),
                        defaultName,
                        defaultName,
                        persist = false,
                    ),
                )
            }
            sessionCoordinator.observeSessions().collect {
                conversation.setSessions(it)
                if (session == null) {
                    // 应用启动时新建对话：每次启动都从空白对话开始（内存态，发首条消息才落盘）
                    conversation.setCurrent(if (uiSettings.value.newChatOnLaunch) {
                        val card = loadCard(defaultName)
                        sessionCoordinator.create(
                            card,
                            defaultName,
                            defaultName,
                            persist = false,
                        )
                    } else it.firstOrNull()?.let { summary -> sessionCoordinator.open(summary.id) })
                }
            }
        }
        // 会话的角色变化时加载对应角色卡，并恢复该角色记忆的模型
        viewModelScope.launch {
            snapshotFlow { session?.charFile }.collectLatest { file ->
                val charFile = file.orEmpty()
                val revision = invalidatePromptContext()
                val snapshot = promptContextDataSource.load(charFile)
                if (session?.charFile.orEmpty() != charFile) return@collectLatest
                applyPromptContext(snapshot, revision)
                // 恢复该角色的模型：角色记忆 > 默认模型聊天卡片
                model.refreshCharacter(currentCard?.name)
            }
        }
        reloadUserProfile()
    }

    override fun onCleared() {
        tts.release()
    }

    val uiState: ChatUiState
        get() = ChatUiState(
            conversation = ChatUiState.ConversationState(
                sessions = sessions,
                current = session,
                card = currentCard,
                characterImage = promptContext.characterImage,
                overlays = overlays,
                displayRegexScripts = displayRegexScripts,
            ),
            input = input.uiState,
            generation = generationCoordinator.uiState,
            profile = ChatUiState.ProfileState(
                userName,
                profile.avatar,
                tts.speakingTimestamp,
                tts.uiState,
            ),
            model = ChatUiState.ModelState(
                apiConfig = apiConfig,
                revision = model.revision,
                displaySpec = displayModelSpec(),
                reasoning = reasoning,
                imageGeneration = imageGeneration,
                imageGenerationAvailable = imageGenerationAvailable,
            ),
            search = ChatUiState.SearchState(
                enabled = searchEnabled,
                providerIndex = searchProviderIndex,
                providerName = searchProviderName,
                services = searchServices,
                builtInAvailable = builtInSearchAvailable,
                builtInEnabled = builtInSearchEnabled,
            ),
            settings = uiSettings.value,
        )

    fun dispatch(action: ChatAction) {
        when (action) {
            ChatAction.SendMessage -> {
                val scrollToBottom = input.editingTimestamp == null
                handleOutcome(sendMessage(), scrollToBottom, hideKeyboard = true)
            }
            is ChatAction.UseQuickReply -> handleOutcome(
                onQuickReply(action.reply),
                scrollToBottom = true,
                hideKeyboard = true,
            )
            is ChatAction.RegenerateAssistant -> handleOutcome(
                regenerateAi(action.timestamp),
                action.scrollToBottom,
            )
            is ChatAction.RegenerateAfterUser -> handleOutcome(
                regenerateAfterUser(action.timestamp),
                action.scrollToBottom,
            )
            ChatAction.NewChat -> newChat()
            is ChatAction.OpenSession -> openSession(action.id)
            is ChatAction.DeleteSession -> deleteSession(action.id)
            is ChatAction.RenameSession -> renameSession(action.id, action.title)
            is ChatAction.SetSessionPinned -> setSessionPinned(action.id, action.pinned)
            is ChatAction.RegenerateTitle -> regenerateTitle(action.id)
            is ChatAction.OpenCharacter -> openCharacter(action.fileName, action.displayName)
            is ChatAction.SelectModel -> selectModel(action.providerId, action.modelId)
            is ChatAction.UpdateReasoning -> updateReasoning(action.level)
            is ChatAction.UpdateImageGeneration -> updateImageGeneration(action.settings)
            ChatAction.StopGeneration -> stopGenerate()
            ChatAction.ToggleSearch -> toggleSearch()
            ChatAction.ToggleBuiltInSearch -> toggleBuiltInSearch()
            is ChatAction.SelectSearchProvider -> selectSearchProvider(action.index)
            is ChatAction.AddAttachments -> input.addAttachments(action.values)
            is ChatAction.RemoveAttachment -> input.removeAttachment(action.value)
            is ChatAction.SetInputText -> input.text = action.text
            ChatAction.CancelEdit -> cancelEdit()
            is ChatAction.StartEdit -> startEdit(action.timestamp)
            is ChatAction.SwitchAlternative -> switchAlt(action.timestamp, action.direction)
            is ChatAction.DeleteMessage -> deleteMessage(action.timestamp)
            is ChatAction.DeleteAllVersions -> deleteAllVersions(action.timestamp)
            is ChatAction.UpdateMessage -> updateMessage(action.timestamp, action.content)
            is ChatAction.ClearOverlay -> clearOverlay(action.timestamp)
            is ChatAction.SpeakMessage -> speakMessage(action.timestamp)
            ChatAction.StopSpeaking -> stopSpeaking()
            ChatAction.PauseSpeaking -> pauseTts()
            ChatAction.ResumeSpeaking -> resumeTts()
            ChatAction.FastForwardSpeaking -> fastForwardTts()
            ChatAction.CycleSpeakingSpeed -> cycleTtsSpeed()
            ChatAction.ReloadUserProfile -> reloadUserProfile()
            is ChatAction.UpdateUserProfile -> updateUserProfile(action.name, action.description)
            ChatAction.ReloadUiSettings -> reloadUiSettings()
            ChatAction.RefreshAfterDataManagement -> refreshAfterDataManagement()
            ChatAction.ReloadApiConfig -> reloadApiConfig()
            ChatAction.ReloadPromptData -> reloadPromptData()
            ChatAction.RefreshCurrentCard -> refreshCurrentCard()
            ChatAction.RefreshExtensionSlots -> refreshExtensionSlots()
            ChatAction.ReloadSearchConfig -> reloadSearchConfig()
        }
    }

    private fun handleOutcome(
        outcome: SendOutcome,
        scrollToBottom: Boolean,
        hideKeyboard: Boolean = false,
    ) {
        if (hideKeyboard && outcome != SendOutcome.SKIPPED) {
            effectChannel.trySend(ChatEffect.HideKeyboard)
        }
        when (outcome) {
            SendOutcome.STARTED -> if (scrollToBottom) effectChannel.trySend(ChatEffect.ScrollToBottom)
            SendOutcome.NO_MODEL -> {
                showMessage(ctx.getString(R.string.select_model_first))
                effectChannel.trySend(ChatEffect.OpenModelSelector)
            }
            SendOutcome.FILE_TOO_LARGE -> showMessage(ctx.getString(R.string.file_too_large_to_send))
            SendOutcome.SKIPPED -> Unit
        }
    }

    private fun showMessage(text: String, long: Boolean = false) {
        effectChannel.trySend(ChatEffect.ShowMessage(text, long))
    }

    // ── 配置 / 用户资料 ──

    /** 当前页面的模型：角色记忆 > ROLE_CHAT > apiConfig.currentModel 回退，全空时返回 null */
    private fun displayModelSpec(): String? = model.effectiveModelSpec(currentCard?.name)

    /** 从 API 配置页返回时刷新：若模型仍在则保持，若模型被删除/禁用则切到全局配置的 currentModel 兜底 */
    private fun reloadApiConfig() {
        model.reload(currentCard?.name)
    }

    /** 从偏好或字号页面返回时刷新聊天页使用的设置快照。 */
    private fun reloadUiSettings() {
        uiSettings.reload()
    }

    private fun selectModel(providerId: String, modelId: String) {
        model.select(currentCard?.name, providerId, modelId)
    }

    private fun updateReasoning(level: ReasoningLevel) {
        model.updateReasoning(currentCard?.name, level)
    }

    private fun updateImageGeneration(settings: ImageGenerationSettings) {
        model.updateImageGeneration(currentCard?.name, settings)
    }

    /** 抽屉里可能改了用户名/头像，关抽屉时刷新 */
    private fun reloadUserProfile() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { profile.load() }
            profile.apply(loaded)
        }
    }

    /** 从角色列表/编辑器返回时刷新当前卡：字段或图片可能已被编辑 */
    private fun refreshCurrentCard() {
        val file = session?.charFile ?: return
        if (file.isBlank()) return
        val revision = invalidatePromptContext()
        viewModelScope.launch {
            applyPromptContext(promptContextDataSource.load(file), revision)
        }
    }

    /** 从世界书/预设页返回或数据管理后刷新：关联内容与预设私有正则可能已被增删改 */
    private fun reloadPromptData() {
        val revision = invalidatePromptContext()
        viewModelScope.launch {
            loadPromptData(revision)
        }
    }

    /** 按当前角色卡加载关联的世界书与预设；保留可用项并向 UI 上报损坏项。 */
    /** 全局启用的正则集（global=true）：对所有聊天生效，与角色关联的集分两条路加载、互不重复 */
    private suspend fun loadPromptData(revision: Long) {
        val file = session?.charFile.orEmpty()
        promptContext.setGlobalRegex(promptContextDataSource.loadGlobalRegex())
        applyPromptContext(promptContextDataSource.load(file), revision)
    }

    private fun invalidatePromptContext(): Long {
        return promptContext.invalidate()
    }

    private fun applyPromptContext(
        snapshot: ChatPromptContextSnapshot,
        revision: Long,
    ) {
        val failures = promptContext.apply(
            loaded = snapshot.loaded,
            image = snapshot.image,
            expectedRevision = revision,
            currentCharFile = session?.charFile.orEmpty(),
        ) ?: return
        if (failures.isNotEmpty()) {
            val names = failures.map { it.name }.distinct().joinToString()
            showMessage(
                ctx.getString(R.string.prompt_context_load_failed_fmt, names),
                long = true,
            )
        }
    }

    private fun currentProviderAndModel(): Pair<ApiProvider, String>? =
        model.resolveProvider(currentCard?.name)

    // ── 会话管理 ──

    private fun openSession(id: String) {
        if (generating) return
        // 悬浮窗是系统级、独立于会话：切换会话不停朗读
        viewModelScope.launch {
            sessionCoordinator.open(id)?.let(conversation::setCurrent)
        }
    }

    /** 顶栏"新聊天"：当前已是无用户消息的新聊天则不重复创建 */
    private fun newChat() {
        val cur = session
        val alreadyNew = cur != null && cur.messages.none { it.role == "user" }
        if (generating || alreadyNew) return
        viewModelScope.launch {
            conversation.setCurrent(
                sessionCoordinator.create(
                    card = currentCard,
                    charFile = cur?.charFile ?: "",
                    charName = cur?.charName ?: "",
                    persist = true,
                ),
            )
        }
    }

    /** 角色选择面板：切到该角色最近的会话；没有则在内存里开新会话（发消息前不落盘）。
     *  偏好"切换角色时新建对话"开启时每次都新建，不再回到该角色的旧会话。 */
    private fun openCharacter(fileName: String, displayName: String) {
        if (generating) return
        viewModelScope.launch {
            if (!uiSettings.value.newChatOnCharSwitch) {
                val existing = sessions.firstOrNull { it.charFile == fileName }
                if (existing != null) {
                    conversation.setCurrent(sessionCoordinator.open(existing.id) ?: existing)
                    return@launch
                }
            }
            val card = if (fileName.isBlank()) null else loadCard(fileName)
            promptContext.setCard(card)
            conversation.setCurrent(
                sessionCoordinator.create(card, fileName, displayName, persist = false),
            )
        }
    }

    private fun deleteSession(id: String) {
        if (!GenerationActionGuard.allowsMutation(generating)) return
        viewModelScope.launch {
            val replacement = sessionCoordinator.delete(
                id = id,
                currentSession = session,
                currentCard = currentCard,
                newChatOnDeleteTopic = uiSettings.value.newChatOnDeleteTopic,
            )
            if (session?.id == id) conversation.setCurrent(replacement)
        }
    }

    private fun renameSession(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            sessionCoordinator.rename(id, trimmed)
            conversation.updateCurrent(id) { it.copy(title = trimmed) }
        }
    }

    private fun setSessionPinned(id: String, pinned: Boolean) {
        viewModelScope.launch {
            sessionCoordinator.setPinned(id, pinned)
            conversation.updateCurrent(id) { it.copy(pinned = pinned) }
        }
    }

    private fun regenerateTitle(id: String) {
        viewModelScope.launch {
            sessionCoordinator.open(id)?.let { generateTitle(it, force = true) }
        }
    }

    /** 数据管理页可能清空了聊天记录/角色卡，返回时重新加载并校验当前会话 */
    private fun refreshAfterDataManagement() {
        val revision = invalidatePromptContext()
        viewModelScope.launch {
            val refreshed = refreshChatData(
                currentSession = session,
                defaultPresetName = ctx.getString(R.string.default_preset),
                defaultCharacterName = ctx.getString(R.string.default_character),
            )
            conversation.setSessions(refreshed.summaries)
            if (refreshed.replaceCard) promptContext.setCard(refreshed.resolvedCard)
            if (refreshed.replaceCurrent) conversation.setCurrent(refreshed.currentSession)
            // 世界书/预设（含预设私有正则）也可能被清空
            loadPromptData(revision)
        }
    }

    // ── 发送 / 重答 ──

    private fun sendMessage(): SendOutcome {
        if (generating) return SendOutcome.SKIPPED
        if (!promptContext.isLoadedFor(session?.charFile.orEmpty())) return SendOutcome.SKIPPED
        val text = input.text.trim()
        val atts = input.attachments
        if (text.isBlank() && atts.isEmpty()) return SendOutcome.SKIPPED
        // 编辑态：只更新该消息文本，不追加新消息、不触发生成
        val editTs = input.editingTimestamp
        if (editTs != null) {
            if (text.isBlank()) return SendOutcome.SKIPPED
            updateMessage(editTs, text)
            input.finishEditing()
            return SendOutcome.STARTED
        }
        val (prov, modelId) = currentProviderAndModel() ?: return SendOutcome.NO_MODEL
        // 发送前同步校验文件大小：过大直接拦截（不清空附件，用户可移除或换文件）
        if (attachmentCoordinator.hasOversizedFile(atts)) {
            return SendOutcome.FILE_TOO_LARGE
        }
        input.clearDraft()
        generationCoordinator.launch generationTask@{
            when (val result = sendChatMessage(
                currentSession = { session },
                text = text,
                pendingAttachments = atts,
                onPrepared = { prepared, userMessage ->
                    conversation.setCurrent(prepared)
                    conversation.putOverlay(userMessage)
                },
                generate = { sessionId ->
                    runGeneration(sessionId, prov, modelId, ChatGenerationMode.SEND, null)
                },
            )) {
                SendChatMessageResult.Completed -> Unit
                SendChatMessageResult.AttachmentFailed -> {
                    restoreDraftAfterFailedSend(text, atts)
                    showMessage(ctx.getString(R.string.attachment_send_failed))
                }
                is SendChatMessageResult.Failed -> {
                    result.restoredSession?.let { restored ->
                        if (session?.id == restored.id) conversation.setCurrent(restored)
                    }
                    result.retainedTimestamps?.let(conversation::retainOverlays)
                    restoreDraftAfterFailedSend(text, atts)
                    val message = if (result.rollbackFailed) {
                        ctx.getString(
                            R.string.chat_send_rollback_failed_fmt,
                            result.error.message.orEmpty(),
                        )
                    } else {
                        ctx.getString(R.string.chat_send_failed_fmt, result.error.message.orEmpty())
                    }
                    showMessage(message)
                    SafeLog.error(CHAT_VIEW_MODEL_TAG, "send_message_failed", result.error)
                }
            }
        }
        return SendOutcome.STARTED
    }

    private fun restoreDraftAfterFailedSend(text: String, sentAttachments: List<Attachment>) {
        input.restoreDraft(text, sentAttachments)
    }

    /** 进入编辑态：把该消息内容填入输入框，发送即更新该消息 */
    private fun startEdit(ts: Long) {
        if (generating) return
        val msg = overlays[ts] ?: session?.messages?.firstOrNull { it.ts == ts } ?: return
        input.beginEditing(ts, msg.content)
    }

    /** 取消编辑：退出编辑态并清空输入 */
    private fun cancelEdit() {
        input.cancelEditing()
    }

    private fun stopGenerate() {
        generationCoordinator.stop()
    }

    /** 切换联网搜索开关（持久化，面板开关据此点亮/熄灭） */
    private fun toggleSearch() {
        search.toggle()
    }

    private fun toggleBuiltInSearch() {
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
        val updatedConfig = apiConfig.copy(providers = apiConfig.providers.map {
            if (it.id == provider.id) updatedProvider else it
        })
        model.updateApiConfig(updatedConfig)
    }

    /** 选择搜索服务商（面板卡片点击，按下标） */
    private fun selectSearchProvider(index: Int) {
        search.selectProvider(index)
    }

    private fun reloadSearchConfig() {
        search.reload()
    }

    /** 朗读/停止朗读指定 AI 消息：同一消息再次点击即停止，换消息则打断旧朗读 */
    private fun speakMessage(ts: Long) {
        val s = session ?: return
        val msg = overlays[ts] ?: s.messages.firstOrNull { it.ts == ts } ?: return
        tts.speak(ts, msg.content)
    }

    private fun stopSpeaking() {
        tts.stop()
    }

    private fun pauseTts() = tts.pause()
    private fun resumeTts() = tts.resume()
    private fun fastForwardTts() = tts.fastForward()

    /** 循环切换朗读速度：0.8x → 1.0x → 1.2x → 1.5x → 0.8x */
    private fun cycleTtsSpeed() {
        tts.cycleSpeed()
    }

    /** 重新计算 UI 插槽类扩展的产出（快捷回复等）。扩展配置变更后调用（如从扩展设置返回）。 */
    private fun refreshExtensionSlots() {
        val qr = mutableListOf<QuickReply>()
        for (ext in dependencies.extensionGateway.enabledExtensions()) {
            if (ext is QuickReplyProvider) {
                qr += ext.quickReplies(dependencies.extensionGateway.config(ext.info.id))
            }
        }
        input.setQuickReplies(qr)
    }

    /** 点击快捷回复：send=true 直接发送，否则插入输入框末尾。 */
    private fun onQuickReply(qr: QuickReply): SendOutcome {
        return if (qr.send) {
            input.text = qr.text
            sendMessage()
        } else {
            input.text += qr.text
            SendOutcome.SKIPPED
        }
    }

    /** AI 消息重答：保留旧版本，新回复作为新版本（可左右切换）；其后的消息保留，由所有版本共享 */
    private fun regenerateAi(ts: Long): SendOutcome {
        if (generating) return SendOutcome.SKIPPED
        if (!promptContext.isLoadedFor(session?.charFile.orEmpty())) return SendOutcome.SKIPPED
        val s = session ?: return SendOutcome.SKIPPED
        val idx = s.messages.indexOfFirst { it.ts == ts }
        // 同步预判仅用于 SendOutcome（提示"先选模型"/是否滚动）；真正的目标计算在 runGeneration
        // 里以 DB 最新态重做，避免读到异步变更（删除/切换）后的陈旧 session 而复活/覆盖消息
        if (idx < 0 || s.messages[idx].role != "assistant") return SendOutcome.SKIPPED
        if (s.messages.take(idx).none { it.role == "user" }) return SendOutcome.SKIPPED  // 开场白不可重答
        val (prov, modelId) = currentProviderAndModel() ?: return SendOutcome.NO_MODEL
        startGenerate(s.id, prov, modelId, ChatGenerationMode.REGENERATE, ts)
        return SendOutcome.STARTED
    }

    /** 用户消息重答：对其后的 AI 回复生成新版本 */
    private fun regenerateAfterUser(ts: Long): SendOutcome {
        if (generating) return SendOutcome.SKIPPED
        if (!promptContext.isLoadedFor(session?.charFile.orEmpty())) return SendOutcome.SKIPPED
        val s = session ?: return SendOutcome.SKIPPED
        val idx = s.messages.indexOfFirst { it.ts == ts }
        if (idx < 0) return SendOutcome.SKIPPED
        val next = s.messages.getOrNull(idx + 1)
        if (next?.role == "assistant") return regenerateAi(next.ts)
        val (prov, modelId) = currentProviderAndModel() ?: return SendOutcome.NO_MODEL
        // 走到这里说明其后没有 AI 回复（正常对话流总是有）：极罕见的用户消息连排场景，
        // 没有分支点可挂下文，直接截断到该用户消息后再生成（截断需先于生成完成，故同一协程内顺序执行）
        generationCoordinator.launch {
            sessionCoordinator.truncateAfter(s.id, ts)
            runGeneration(s.id, prov, modelId, ChatGenerationMode.SEND, null)
        }
        return SendOutcome.STARTED
    }

    private fun startGenerate(
        sessionId: String,
        prov: ApiProvider,
        modelId: String,
        mode: ChatGenerationMode,
        targetTs: Long?,
    ) {
        generationCoordinator.launch {
            runGeneration(sessionId, prov, modelId, mode, targetTs)
        }
    }

    /**
     * 一次生成的核心：以 DB 最新态重取会话，据 [mode] 组装目标消息（SEND 追加新 assistant；
     * REGEN 在 [targetTs] 上开新版本），流式内容走 overlay（不落盘空行），收尾只写一次最终消息。
     */
    private suspend fun runGeneration(
        sessionId: String,
        prov: ApiProvider,
        modelId: String,
        mode: ChatGenerationMode,
        targetTs: Long?,
    ) {
        val result = generationRunner.run(
            request = ChatGenerationRunner.Request(
                sessionId = sessionId,
                provider = prov,
                modelId = modelId,
                mode = mode,
                targetTimestamp = targetTs,
                card = currentCard,
                userName = userName,
                worldBooks = promptContext.worldBooks,
                preset = promptContext.preset,
                promptRegex = displayRegexScripts,
                reasoning = reasoning,
                imageGeneration = imageGeneration,
                searchEnabled = searchEnabled,
            ),
            onStarted = { base, message ->
                conversation.setCurrent(base)
                generationCoordinator.markTarget(message.ts)
                conversation.putOverlay(message)
            },
            onLocalVariablesCommitted = { variables ->
                if (session?.id == sessionId) {
                    conversation.updateCurrent(sessionId) { it.copy(localVariables = variables) }
                }
            },
            onUpdate = { message ->
                conversation.putOverlay(message)
            },
        ) ?: return
        result.completedSession?.let { done ->
            if (session?.id == sessionId) conversation.setCurrent(done)
            runExtensionLifecycle(done)
            maybeGenerateTitle(done)
        }
    }

    /** 生成完成后跑扩展生命周期钩子（如摘要）：后台执行、失败隔离，完成后同步内存会话的扩展状态。 */
    private fun runExtensionLifecycle(done: ChatSession) {
        postGenerationCoordinator.runExtensions(
            session = done,
            apiConfig = apiConfig,
            userName = userName,
            characterName = currentCard?.name ?: done.charName,
            isCurrent = { session?.id == done.id },
            onSessionRefreshed = { fresh ->
                conversation.updateCurrent(done.id) { it.copy(extState = fresh.extState) }
            },
        )
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
        val chatModel = displayModelSpec() ?: return
        postGenerationCoordinator.generateTitle(
            session = session,
            force = force,
            chatModel = chatModel,
            apiConfig = apiConfig,
            userName = userName,
            characterName = currentCard?.name ?: session.charName,
            onTitle = { title ->
                if (session.id == this@ChatViewModel.session?.id) {
                    conversation.updateCurrent(session.id) { it.copy(title = title) }
                }
            },
        )
    }

    // ── 消息操作（统一走 DB：按 ts 定位单条消息落盘，分页由 Room 自动刷新） ──
    //   写库异步、分页刷新有时间差，故变更先进 overlay 即时反映（滚动锚定/避免陈旧闪烁），
    //   DB 回来后把 overlay 校准到权威结果，最终由 UI 在分页补齐后 clearOverlay 撤下。

    /** 左右切换消息版本（DB 落盘 + 乐观 overlay 即时切换，供锚定同帧读到新内容） */
    private fun switchAlt(ts: Long, dir: Int) {
        if (generating) return
        val s = session ?: return
        val optimistic = conversation.switchAlternative(ts, dir) ?: return
        viewModelScope.launch {
            messageCoordinator.switchAlt(s, ts, dir)?.let { fresh ->
                reconcileMessageMutation(s.id, ts, fresh, expectedOverlay = optimistic)
            }
        }
    }

    /** 删除消息：多版本时只删当前显示的版本（下文不受影响），单版本删除整条 */
    private fun deleteMessage(ts: Long) {
        if (generating) return
        val s = session ?: return
        // 删除是"移除行"，overlay 无法表示；撤掉该 ts 可能存在的 overlay，直接走 DB + 分页刷新
        conversation.removeOverlay(ts)
        viewModelScope.launch {
            messageCoordinator.deleteMessage(s, ts)?.let { fresh ->
                reconcileMessageMutation(s.id, ts, fresh)
            }
        }
    }

    /** 删除消息的全部版本（整条消息） */
    private fun deleteAllVersions(ts: Long) {
        if (generating) return
        val s = session ?: return
        conversation.removeOverlay(ts)
        viewModelScope.launch {
            messageCoordinator.deleteAllVersions(s, ts)?.let { fresh ->
                reconcileMessageMutation(s.id, ts, fresh)
            }
        }
    }

    private fun updateMessage(ts: Long, content: String) {
        val s = session ?: return
        val cur = overlays[ts] ?: s.messages.firstOrNull { it.ts == ts }
        if (cur != null) conversation.putOverlay(cur.copy(content = content))
        viewModelScope.launch {
            messageCoordinator.updateMessage(s, ts, content)?.let { fresh ->
                reconcileMessageMutation(s.id, ts, fresh)
            }
        }
    }

    /** UI 检测到分页已把该 ts 的最终内容补齐后调用：撤下顶替显示的 overlay */
    private fun clearOverlay(ts: Long) {
        conversation.removeOverlay(ts)
    }

    /** 单条 DB 变更后把 overlay 校准到 DB 权威结果并同步内存会话（陈旧乐观值在此被纠正） */
    private fun reconcileMessageMutation(
        sid: String,
        ts: Long,
        fresh: ChatSession,
        expectedOverlay: ChatMessage? = null,
    ) {
        conversation.reconcileMessage(sid, ts, fresh, expectedOverlay)
    }

    private fun updateUserProfile(name: String, description: String) {
        profile.save(name, description)
        reloadUserProfile()
        viewModelScope.launch {
            promptContext.setGlobalRegex(promptContextDataSource.loadGlobalRegex())
        }
    }

    // ── IO 辅助 ──

    private suspend fun loadCard(file: String): CharacterCard? =
        promptContextDataSource.loadCard(file)

}
