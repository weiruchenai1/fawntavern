package me.rerere.fawntavern.ui.chat

import android.app.Application
import me.rerere.fawntavern.core.diagnostics.SafeLog
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.domain.GenerationEngine
import me.rerere.fawntavern.domain.GenerationActionGuard
import me.rerere.fawntavern.domain.PromptBuilder
import me.rerere.fawntavern.domain.PromptLog
import me.rerere.fawntavern.domain.ChatRegenerationPlan
import me.rerere.fawntavern.domain.ChatRegenerationPlanner
import me.rerere.fawntavern.domain.chat.ChatMessageCoordinator
import me.rerere.fawntavern.domain.chat.CommitChatGenerationUseCase
import me.rerere.fawntavern.domain.chat.ChatSessionCoordinator
import me.rerere.fawntavern.domain.chat.RepositoryChatSessionDataSource
import me.rerere.fawntavern.extension.BuiltinExtensions
import me.rerere.fawntavern.extension.QuickReply

private const val CHAT_VIEW_MODEL_TAG = "ChatViewModel"

/**
 * 聊天状态容器：组合 UI 状态持有者并把界面动作调度到用例/协调器。
 * 业务逻辑在 domain 层：Prompt 拼装 → [PromptBuilder]，
 * 会话/消息变换由状态持有者与用例处理，流式生成由 [GenerationEngine] 处理。
 * 生成协程运行在 viewModelScope —— Activity 因深色模式/语言切换等重建时不中断，状态不丢失。
 * UI（ChatScreen）只读取 [uiState]、发送 [ChatAction]、处理 [ChatEffect]。
 */
internal class ChatViewModel(
    app: Application,
    private val dependencies: ChatFeatureDependencies,
) : AndroidViewModel(app) {

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
    private val promptContextCoordinator by lazy {
        ChatPromptContextCoordinator(
            dataSource = promptContextDataSource,
            state = promptContext,
            currentCharFile = { session?.charFile.orEmpty() },
            onLoadFailures = { failures ->
                val names = failures.map { it.name }.distinct().joinToString()
                showMessage(
                    ctx.getString(R.string.prompt_context_load_failed_fmt, names),
                    long = true,
                )
            },
        )
    }
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
    private val profileCoordinator by lazy {
        ChatProfileCoordinator(viewModelScope, profile, promptContextCoordinator)
    }
    private val userName get() = profile.name
    private val input = ChatInputStateHolder()
    private val quickReplies by lazy {
        ChatQuickReplyCoordinator(dependencies.extensionGateway, input)
    }
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
    private val modelCapabilities: ChatModelCapabilities
        get() = model.capabilities(currentCard?.name)
    private val tts = ChatTtsStateHolder(app, viewModelScope)

    private val displayRegexScripts: List<CharRegex>
        get() = promptContext.displayRegex

    private val chatRepository = dependencies.chatRepository
    private val generation = GenerationEngine(dependencies.generationGateway)
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
        get() = generationOrchestrator.isRunning
    private val messageCoordinator by lazy { ChatMessageCoordinator(chatRepository) }
    private val messageMutations by lazy {
        ChatMessageMutationCoordinator(viewModelScope, messageCoordinator, conversation)
    }
    private val attachmentCoordinator by lazy {
        ChatAttachmentCoordinator(dependencies.attachmentDataSource)
    }
    private val sendChatMessage by lazy {
        SendChatMessageUseCase(chatRepository, attachmentCoordinator)
    }
    private val sendCoordinator by lazy {
        ChatSendCoordinator(
            input = input,
            conversation = conversation,
            promptContext = promptContext,
            attachments = attachmentCoordinator,
            messageMutations = messageMutations,
            sendMessage = sendChatMessage,
            generation = generationOrchestrator,
            resolveModel = ::currentProviderAndModel,
            onFailure = ::handleSendFailure,
        )
    }
    private val refreshChatData by lazy {
        RefreshChatDataUseCase(chatRepository, promptContextDataSource)
    }
    private val dataRefresh by lazy {
        ChatDataRefreshCoordinator(
            scope = viewModelScope,
            refreshUseCase = refreshChatData,
            conversation = conversation,
            promptState = promptContext,
            promptContext = promptContextCoordinator,
        )
    }
    private val sessionCoordinator by lazy {
        ChatSessionCoordinator(RepositoryChatSessionDataSource(chatRepository))
    }
    private val startupCoordinator by lazy {
        ChatStartupCoordinator(sessionCoordinator, promptContextDataSource)
    }
    private val sessionActions by lazy {
        ChatSessionActionCoordinator(
            scope = viewModelScope,
            sessions = sessionCoordinator,
            resources = promptContextDataSource,
            conversation = conversation,
            promptContext = promptContext,
            newChatOnCharacterSwitch = { uiSettings.value.newChatOnCharSwitch },
            newChatOnDelete = { uiSettings.value.newChatOnDeleteTopic },
        )
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
    private val generationOrchestrator by lazy {
        ChatGenerationOrchestrator(
            scope = viewModelScope,
            runner = generationRunner,
            generationState = generationCoordinator,
            conversation = conversation,
            sessions = sessionCoordinator,
            postGeneration = postGenerationCoordinator,
            snapshot = ::generationSnapshot,
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
        quickReplies.refresh()
        // Prompt 调试日志开关：把持久化设置同步到内存 sink（关闭时生成不记录）
        PromptLog.enabled = dependencies.promptLogEnabled
        // 会话列表来自 Repository 的 Flow：任何 save/delete/clear 后自动刷新
        viewModelScope.launch {
            startupCoordinator.observe(
                defaultPresetName = ctx.getString(R.string.default_preset),
                defaultCharacterName = ctx.getString(R.string.default_character),
                newChatOnLaunch = { uiSettings.value.newChatOnLaunch },
                currentSession = { session },
                onSessions = conversation::replaceSessions,
                onSessionSelected = conversation::replaceCurrent,
            )
        }
        // 会话的角色变化时加载对应角色卡，并恢复该角色记忆的模型
        viewModelScope.launch {
            snapshotFlow { session?.charFile }.collectLatest { file ->
                val charFile = file.orEmpty()
                if (!promptContextCoordinator.refresh(charFile)) return@collectLatest
                // 恢复该角色的模型：角色记忆 > 默认模型聊天卡片
                model.refreshCharacter(currentCard?.name)
            }
        }
        profileCoordinator.reload()
    }

    override fun onCleared() {
        tts.release()
    }

    val uiState: ChatUiState
        get() = ChatUiState(
            conversation = ChatConversationState(
                sessions = sessions,
                current = session,
                card = currentCard,
                characterImage = promptContext.characterImage,
                overlays = overlays,
                displayRegexScripts = displayRegexScripts,
            ),
            input = input.state,
            generation = generationOrchestrator.uiState,
            profile = ChatProfileState(
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
                imageGenerationAvailable = modelCapabilities.imageGenerationAvailable,
            ),
            search = ChatSearchState(
                enabled = searchEnabled,
                providerIndex = searchProviderIndex,
                providerName = searchProviderName,
                services = searchServices,
                builtInAvailable = modelCapabilities.builtInSearchAvailable,
                builtInEnabled = modelCapabilities.builtInSearchEnabled,
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
        outcome: ChatSendOutcome,
        scrollToBottom: Boolean,
        hideKeyboard: Boolean = false,
    ) {
        if (hideKeyboard && outcome != ChatSendOutcome.SKIPPED) {
            effectChannel.trySend(ChatEffect.HideKeyboard)
        }
        when (outcome) {
            ChatSendOutcome.STARTED -> if (scrollToBottom) effectChannel.trySend(ChatEffect.ScrollToBottom)
            ChatSendOutcome.NO_MODEL -> {
                showMessage(ctx.getString(R.string.select_model_first))
                effectChannel.trySend(ChatEffect.OpenModelSelector)
            }
            ChatSendOutcome.FILE_TOO_LARGE -> showMessage(ctx.getString(R.string.file_too_large_to_send))
            ChatSendOutcome.SKIPPED -> Unit
        }
    }

    private fun handleSendFailure(failure: ChatSendFailure) {
        when (failure) {
            ChatSendFailure.Attachment -> showMessage(ctx.getString(R.string.attachment_send_failed))
            is ChatSendFailure.Send -> {
                val message = if (failure.rollbackFailed) {
                    ctx.getString(
                        R.string.chat_send_rollback_failed_fmt,
                        failure.error.message.orEmpty(),
                    )
                } else {
                    ctx.getString(R.string.chat_send_failed_fmt, failure.error.message.orEmpty())
                }
                showMessage(message)
            }
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
        profileCoordinator.reload()
    }

    /** 从角色列表/编辑器返回时刷新当前卡：字段或图片可能已被编辑 */
    private fun refreshCurrentCard() {
        val file = session?.charFile ?: return
        if (file.isBlank()) return
        viewModelScope.launch {
            promptContextCoordinator.refresh(file)
        }
    }

    /** 从世界书/预设页返回或数据管理后刷新：关联内容与预设私有正则可能已被增删改 */
    private fun reloadPromptData() {
        viewModelScope.launch {
            promptContextCoordinator.refresh(includeGlobalRegex = true)
        }
    }

    private fun currentProviderAndModel(): Pair<ApiProvider, String>? =
        model.resolveProvider(currentCard?.name)

    // ── 会话管理 ──

    private fun openSession(id: String) {
        if (generating) return
        sessionActions.open(id)
    }

    /** 顶栏"新聊天"：当前已是无用户消息的新聊天则不重复创建 */
    private fun newChat() {
        if (generating) return
        sessionActions.createNew()
    }

    /** 角色选择面板：切到该角色最近的会话；没有则在内存里开新会话（发消息前不落盘）。
     *  偏好"切换角色时新建对话"开启时每次都新建，不再回到该角色的旧会话。 */
    private fun openCharacter(fileName: String, displayName: String) {
        if (generating) return
        sessionActions.openCharacter(fileName, displayName)
    }

    private fun deleteSession(id: String) {
        if (!GenerationActionGuard.allowsMutation(generating)) return
        sessionActions.delete(id)
    }

    private fun renameSession(id: String, title: String) {
        sessionActions.rename(id, title)
    }

    private fun setSessionPinned(id: String, pinned: Boolean) {
        sessionActions.setPinned(id, pinned)
    }

    private fun regenerateTitle(id: String) {
        generationOrchestrator.generateTitle(id)
    }

    /** 数据管理页可能清空了聊天记录/角色卡，返回时重新加载并校验当前会话 */
    private fun refreshAfterDataManagement() {
        dataRefresh.refresh(
            defaultPresetName = ctx.getString(R.string.default_preset),
            defaultCharacterName = ctx.getString(R.string.default_character),
        )
    }

    // ── 发送 / 重答 ──

    private fun sendMessage(): ChatSendOutcome = sendCoordinator.send()

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
        generationOrchestrator.stop()
    }

    /** 切换联网搜索开关（持久化，面板开关据此点亮/熄灭） */
    private fun toggleSearch() {
        search.toggle()
    }

    private fun toggleBuiltInSearch() {
        model.toggleBuiltInSearch(currentCard?.name)
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
        quickReplies.refresh()
    }

    /** 点击快捷回复：send=true 直接发送，否则插入输入框末尾。 */
    private fun onQuickReply(qr: QuickReply): ChatSendOutcome {
        return if (qr.send) {
            input.text = qr.text
            sendMessage()
        } else {
            input.text += qr.text
            ChatSendOutcome.SKIPPED
        }
    }

    /** AI 消息重答：保留旧版本，新回复作为新版本（可左右切换）；其后的消息保留，由所有版本共享 */
    private fun regenerateAi(ts: Long): ChatSendOutcome {
        if (generating) return ChatSendOutcome.SKIPPED
        if (!promptContext.isLoadedFor(session?.charFile.orEmpty())) return ChatSendOutcome.SKIPPED
        val s = session ?: return ChatSendOutcome.SKIPPED
        val plan = ChatRegenerationPlanner.forAssistant(s, ts) ?: return ChatSendOutcome.SKIPPED
        return executeRegeneration(s, plan)
    }

    /** 用户消息重答：对其后的 AI 回复生成新版本 */
    private fun regenerateAfterUser(ts: Long): ChatSendOutcome {
        if (generating) return ChatSendOutcome.SKIPPED
        if (!promptContext.isLoadedFor(session?.charFile.orEmpty())) return ChatSendOutcome.SKIPPED
        val s = session ?: return ChatSendOutcome.SKIPPED
        val plan = ChatRegenerationPlanner.afterUser(s, ts) ?: return ChatSendOutcome.SKIPPED
        return executeRegeneration(s, plan)
    }

    private fun executeRegeneration(
        session: ChatSession,
        plan: ChatRegenerationPlan,
    ): ChatSendOutcome {
        val (prov, modelId) = currentProviderAndModel() ?: return ChatSendOutcome.NO_MODEL
        generationOrchestrator.launchRegeneration(session, prov, modelId, plan)
        return ChatSendOutcome.STARTED
    }

    private fun generationSnapshot(): ChatGenerationSnapshot = ChatGenerationSnapshot(
        card = currentCard,
        userName = userName,
        worldBooks = promptContext.worldBooks,
        preset = promptContext.preset,
        promptRegex = displayRegexScripts,
        reasoning = reasoning,
        imageGeneration = imageGeneration,
        searchEnabled = searchEnabled,
        apiConfig = apiConfig,
        chatModel = displayModelSpec(),
    )

    // ── 消息操作（统一走 DB：按 ts 定位单条消息落盘，分页由 Room 自动刷新） ──
    //   写库异步、分页刷新有时间差，故变更先进 overlay 即时反映（滚动锚定/避免陈旧闪烁），
    //   DB 回来后把 overlay 校准到权威结果，最终由 UI 在分页补齐后 clearOverlay 撤下。

    /** 左右切换消息版本（DB 落盘 + 乐观 overlay 即时切换，供锚定同帧读到新内容） */
    private fun switchAlt(ts: Long, dir: Int) {
        if (generating) return
        messageMutations.switchAlternative(ts, dir)
    }

    /** 删除消息：多版本时只删当前显示的版本（下文不受影响），单版本删除整条 */
    private fun deleteMessage(ts: Long) {
        if (generating) return
        messageMutations.deleteMessage(ts)
    }

    /** 删除消息的全部版本（整条消息） */
    private fun deleteAllVersions(ts: Long) {
        if (generating) return
        messageMutations.deleteAllVersions(ts)
    }

    private fun updateMessage(ts: Long, content: String) {
        messageMutations.updateMessage(ts, content)
    }

    /** UI 检测到分页已把该 ts 的最终内容补齐后调用：撤下顶替显示的 overlay */
    private fun clearOverlay(ts: Long) {
        conversation.removeOverlay(ts)
    }

    private fun updateUserProfile(name: String, description: String) {
        profileCoordinator.update(name, description)
    }

}
