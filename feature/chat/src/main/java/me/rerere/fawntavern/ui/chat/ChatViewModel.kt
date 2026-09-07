package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.core.diagnostics.SafeLog
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.domain.GenerationEngine
import me.rerere.fawntavern.domain.GenerationActionGuard
import me.rerere.fawntavern.domain.PromptBuilder
import me.rerere.fawntavern.domain.ChatRegenerationPlan
import me.rerere.fawntavern.domain.ChatRegenerationPlanner
import me.rerere.fawntavern.domain.chat.ChatMessageCoordinator
import me.rerere.fawntavern.domain.chat.CommitChatGenerationUseCase
import me.rerere.fawntavern.domain.chat.ChatSessionCoordinator
import me.rerere.fawntavern.extension.QuickReply
import org.json.JSONObject

private const val CHAT_VIEW_MODEL_TAG = "ChatViewModel"

/**
 * 聊天状态容器：组合 UI 状态持有者并把界面动作调度到用例/协调器。
 * 业务逻辑在 domain 层：Prompt 拼装 → [PromptBuilder]，
 * 会话/消息变换由状态持有者与用例处理，流式生成由 [GenerationEngine] 处理。
 * 生成协程运行在 viewModelScope —— Activity 因深色模式/语言切换等重建时不中断，状态不丢失。
 * UI（ChatScreen）只读取 [uiState]、发送 [ChatAction]、处理 [ChatEffect]。
 */
class ChatViewModel(
    private val dependencies: ChatFeatureDependencies,
) : ViewModel() {

    private val sessionDependencies = dependencies.session
    private val generationDependencies = dependencies.generation
    private val platformDependencies = dependencies.platform

    private val effectChannel = Channel<ChatEffect>(Channel.BUFFERED)
    val effects: Flow<ChatEffect> = effectChannel.receiveAsFlow()
    private val frontendEventSequence = AtomicLong()
    private val _frontendEvents = MutableSharedFlow<ChatFrontendEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frontendEvents: SharedFlow<ChatFrontendEvent> = _frontendEvents

    // ── 状态（写入只经由本类方法） ──
    private val model = ChatModelStateHolder(
        ChatModelController(platformDependencies.modelDataSource),
        generationDependencies.apiConfigRepository,
    )
    private val apiConfig get() = model.apiConfig
    private val uiSettings = ChatUiSettingsStateHolder(
        ChatUiSettingsController(platformDependencies.uiSettingsDataSource),
    )
    /** 当前模型的思考预算档位（按模型记忆，随选模型切换）；AUTO = 不下发任何思考字段 */
    private val reasoning get() = model.reasoning
    /** 当前模型的图片生成控制项（按模型记忆）。 */
    private val imageGeneration get() = model.imageGeneration
    private val conversation = ChatConversationStateHolder()
    private val sessions get() = conversation.sessions
    private val session get() = conversation.current
    private val promptContext = ChatPromptContextStateHolder()
    private val promptContextDataSource = sessionDependencies.promptContextDataSource
    private val promptContextCoordinator by lazy {
        ChatPromptContextCoordinator(
            dataSource = promptContextDataSource,
            state = promptContext,
            currentCharFile = { session?.charFile.orEmpty() },
            onLoadFailures = { failures ->
                val names = failures.map { it.name }.distinct().joinToString()
                showMessage(
                    platformDependencies.texts.promptContextLoadFailed(names),
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
        ChatUserProfileController(sessionDependencies.userProfileDataSource),
    )
    private val profileCoordinator by lazy {
        ChatProfileCoordinator(viewModelScope, profile, promptContextCoordinator)
    }
    private val userName get() = profile.name
    private val input = ChatInputStateHolder()
    private val quickReplies by lazy {
        ChatQuickReplyCoordinator(generationDependencies.extensionGateway, input)
    }
    val inputState get() = input.textFieldState

    private val search = ChatSearchStateHolder(
        ChatWebSearchSettingsController(platformDependencies.searchSettingsDataSource),
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
        get() = model.capabilities()
    private val tts = ChatTtsStateHolder(platformDependencies.ttsControllerFactory, viewModelScope)

    private val displayRegexScripts: List<CharRegex>
        get() = promptContext.displayRegex

    private val chatRepository = sessionDependencies.chatRepository
    private var globalVariables by mutableStateOf(generationDependencies.promptEnvironment.globalVariables())
    private val variableWriteMutex = Mutex()
    private val frontendRpcController by lazy {
        ChatFrontendRpcController(
            repository = chatRepository,
            currentSession = { session },
            replaceCurrent = { updated ->
                if (session?.id == updated.id) conversation.replacePersistedCurrent(updated)
            },
            loadGlobalVariables = generationDependencies.promptEnvironment::globalVariables,
            saveGlobalVariables = { values ->
                generationDependencies.generationResources.saveGlobalVariables(values)
                globalVariables = values
            },
            scopedVariables = sessionDependencies.frontendVariableDataSource,
            scopeOwner = { scope, params ->
                when (scope) {
                    "character" -> session?.charFile.orEmpty()
                    "preset" -> currentCard?.linkedPresetId.orEmpty()
                    "script" -> params.optString("script_id").ifBlank {
                        "message:${session?.id.orEmpty()}:${params.optInt("message_id", -1)}"
                    }
                    else -> ""
                }.also { require(it.isNotBlank()) { "No active owner for $scope variables" } }
            },
            emitEvent = ::emitFrontendEvent,
        )
    }
    private val frontendGeneration by lazy {
        ChatFrontendGenerationController(
            config = { apiConfig },
            gateway = generationDependencies.generationGateway,
            emitEvent = ::emitFrontendEvent,
        )
    }
    private val generation = GenerationEngine(generationDependencies.generationGateway)
    private val generationCoordinator by lazy {
        ChatGenerationCoordinator(
            scope = viewModelScope,
            stopCurrent = generation::stop,
            onFailure = { error ->
                SafeLog.error(CHAT_VIEW_MODEL_TAG, "generation_failed", error)
                showMessage(platformDependencies.texts.generationFailed(error.message.orEmpty()))
            },
        )
    }
    private val generating: Boolean
        get() = generationOrchestrator.isRunning
    private val messageCoordinator by lazy { ChatMessageCoordinator(chatRepository) }
    private val messageMutations by lazy {
        ChatMessageMutationCoordinator(
            scope = viewModelScope,
            persistence = messageCoordinator,
            conversation = conversation,
            onFailure = ::handleOperationFailure,
            onCommitted = { event, timestamp ->
                emitFrontendEvent(event, JSONObject().put("message_ts", timestamp).toString())
            },
        )
    }
    private val attachmentCoordinator by lazy {
        ChatAttachmentCoordinator(sessionDependencies.attachmentDataSource)
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
            canSend = { !sessionActions.isSelecting },
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
        ChatSessionCoordinator(chatRepository)
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
            canSelect = { !generating },
            onFailure = ::handleOperationFailure,
            onSelected = { id ->
                emitFrontendEvent("chat_id_changed", JSONObject().put("chat_id", id).toString())
            },
        )
    }
    private val generationRunner by lazy {
        val promptAssembler = ChatPromptAssembler(generationDependencies.promptEnvironment)
        ChatGenerationRunner(
            chatRepository = chatRepository,
            generation = generation,
            resources = generationDependencies.generationResources,
            prepare = PrepareChatGenerationUseCase(chatRepository, promptAssembler),
            commit = CommitChatGenerationUseCase(chatRepository),
            searchTool = ChatSearchTool(generationDependencies.searchToolDataSource),
        )
    }
    private val postGenerationCoordinator by lazy {
        ChatPostGenerationCoordinator(
            scope = viewModelScope,
            chatRepository = chatRepository,
            extensions = generationDependencies.extensionGateway,
            titleGenerator = ChatTitleGenerator(
                chatRepository = chatRepository,
                extensions = generationDependencies.extensionGateway,
                settings = generationDependencies.titleSettingsDataSource,
            ),
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
            onFrontendEvent = ::emitFrontendEvent,
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
        platformDependencies.initialize()
        viewModelScope.launch {
            generationDependencies.promptEnvironment.observeGlobalVariables().collect { globalVariables = it }
        }
        quickReplies.refresh()
        // 会话列表来自 Repository 的 Flow：任何 save/delete/clear 后自动刷新
        viewModelScope.launch {
            startupCoordinator.observe(
                defaultPresetName = platformDependencies.texts.defaultPresetName,
                defaultCharacterName = platformDependencies.texts.defaultCharacterName,
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
        get() {
            return ChatUiState(
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
                globalVariables = globalVariables,
            )
        }

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
            is ChatAction.RenameSession -> sessionActions.rename(action.id, action.title)
            is ChatAction.SetSessionPinned -> sessionActions.setPinned(action.id, action.pinned)
            is ChatAction.RegenerateTitle -> generationOrchestrator.generateTitle(action.id)
            is ChatAction.OpenCharacter -> openCharacter(action.fileName, action.displayName)
            is ChatAction.SelectModel -> model.select(currentCard?.name, action.providerId, action.modelId)
            is ChatAction.UpdateReasoning -> model.updateReasoning(action.level)
            is ChatAction.UpdateImageGeneration -> model.updateImageGeneration(action.settings)
            ChatAction.StopGeneration -> generationOrchestrator.stop()
            ChatAction.ToggleSearch -> search.toggle()
            ChatAction.ToggleBuiltInSearch -> model.toggleBuiltInSearch()
            is ChatAction.SelectSearchProvider -> search.selectProvider(action.index)
            is ChatAction.AddAttachments -> input.addAttachments(action.values)
            is ChatAction.RemoveAttachment -> input.removeAttachment(action.value)
            is ChatAction.SetInputText -> input.text = action.text
            ChatAction.CancelEdit -> input.cancelEditing()
            is ChatAction.StartEdit -> startEdit(action.message)
            is ChatAction.SwitchAlternative -> switchAlt(action.message, action.direction)
            is ChatAction.DeleteMessage -> deleteMessage(action.timestamp)
            is ChatAction.DeleteAllVersions -> deleteAllVersions(action.timestamp)
            is ChatAction.UpdateMessage -> updateMessage(action.message, action.content)
            is ChatAction.ReplaceFrontendVariables ->
                replaceFrontendVariables(action.scope, action.values)
            is ChatAction.ClearOverlay -> conversation.removeOverlay(action.timestamp)
            is ChatAction.SpeakMessage -> speakMessage(action.message)
            ChatAction.StopSpeaking -> tts.stop()
            ChatAction.PauseSpeaking -> tts.pause()
            ChatAction.ResumeSpeaking -> tts.resume()
            ChatAction.FastForwardSpeaking -> tts.fastForward()
            ChatAction.CycleSpeakingSpeed -> tts.cycleSpeed()
            ChatAction.ReloadUserProfile -> profileCoordinator.reload()
            is ChatAction.UpdateUserProfile -> profileCoordinator.update(action.name, action.description)
            ChatAction.ReloadUiSettings -> uiSettings.reload()
            ChatAction.RefreshAfterDataManagement -> refreshAfterDataManagement()
            ChatAction.ReloadApiConfig -> model.reload(currentCard?.name)
            ChatAction.ReloadPromptData -> reloadPromptData()
            ChatAction.RefreshCurrentCard -> refreshCurrentCard()
            ChatAction.RefreshExtensionSlots -> quickReplies.refresh()
            ChatAction.ReloadSearchConfig -> search.reload()
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
            ChatSendOutcome.STARTED -> {
                emitFrontendEvent("message_sent", JSONObject().put("message_id", session?.totalMessageCount ?: -1).toString())
                if (scrollToBottom) effectChannel.trySend(ChatEffect.ScrollToBottom)
            }
            ChatSendOutcome.NO_MODEL -> {
                showMessage(platformDependencies.texts.selectModelFirst)
                effectChannel.trySend(ChatEffect.OpenModelSelector)
            }
            ChatSendOutcome.FILE_TOO_LARGE -> showMessage(platformDependencies.texts.fileTooLarge)
            ChatSendOutcome.SKIPPED -> Unit
        }
    }

    private fun handleSendFailure(failure: ChatSendFailure) {
        when (failure) {
            ChatSendFailure.Attachment -> showMessage(platformDependencies.texts.attachmentFailed)
            is ChatSendFailure.Send -> {
                val message = if (failure.rollbackFailed) {
                    platformDependencies.texts.rollbackFailed(failure.error.message.orEmpty())
                } else {
                    platformDependencies.texts.sendFailed(failure.error.message.orEmpty())
                }
                showMessage(message)
            }
        }
    }

    private fun showMessage(text: String, long: Boolean = false) {
        effectChannel.trySend(ChatEffect.ShowMessage(text, long))
    }

    private fun handleOperationFailure(error: Exception) {
        SafeLog.error(CHAT_VIEW_MODEL_TAG, "chat_operation_failed", error)
        showMessage(platformDependencies.texts.operationFailed(error.message.orEmpty()))
    }

    // ── 配置 / 用户资料 ──

    /** 当前页面的模型：角色记忆 > ROLE_CHAT > apiConfig.currentModel 回退，全空时返回 null */
    private fun displayModelSpec(): String? = model.selectedModelSpec

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
        model.resolveProvider()

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

    /**
     * 数据管理页可能恢复了任意备份分区。返回时同步刷新配置快照，并重新加载、校验
     * 聊天记录与提示上下文；全局变量由存储变化流更新，TTS 由使用方读取。
     */
    private fun refreshAfterDataManagement() {
        model.reload(currentCard?.name)
        search.reload()
        profileCoordinator.reload()
        dataRefresh.refresh(
            defaultPresetName = platformDependencies.texts.defaultPresetName,
            defaultCharacterName = platformDependencies.texts.defaultCharacterName,
        )
    }

    // ── 发送 / 重答 ──

    private fun sendMessage(): ChatSendOutcome = sendCoordinator.send()

    /** 进入编辑态：把该消息内容填入输入框，发送即更新该消息 */
    private fun startEdit(message: ChatMessage) {
        if (generating) return
        val current = overlays[message.ts] ?: message
        input.beginEditing(current)
    }

    /** 朗读/停止朗读指定 AI 消息：同一消息再次点击即停止，换消息则打断旧朗读 */
    private fun speakMessage(message: ChatMessage) {
        val current = overlays[message.ts] ?: message
        tts.speak(current.ts, current.content)
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
        if (generating || sessionActions.isSelecting) return ChatSendOutcome.SKIPPED
        if (!promptContext.isLoadedFor(session?.charFile.orEmpty())) return ChatSendOutcome.SKIPPED
        val sessionId = session?.id ?: return ChatSendOutcome.SKIPPED
        return launchRegeneration(sessionId) { ChatRegenerationPlanner.forAssistant(it, ts) }
    }

    /** 用户消息重答：对其后的 AI 回复生成新版本 */
    private fun regenerateAfterUser(ts: Long): ChatSendOutcome {
        if (generating || sessionActions.isSelecting) return ChatSendOutcome.SKIPPED
        if (!promptContext.isLoadedFor(session?.charFile.orEmpty())) return ChatSendOutcome.SKIPPED
        val sessionId = session?.id ?: return ChatSendOutcome.SKIPPED
        return launchRegeneration(sessionId) { ChatRegenerationPlanner.afterUser(it, ts) }
    }

    private fun launchRegeneration(
        sessionId: String,
        createPlan: (ChatSession) -> ChatRegenerationPlan?,
    ): ChatSendOutcome {
        val (prov, modelId) = currentProviderAndModel() ?: return ChatSendOutcome.NO_MODEL
        val started = generationOrchestrator.launchRegeneration(sessionId, prov, modelId, createPlan)
        return if (started) ChatSendOutcome.STARTED else ChatSendOutcome.SKIPPED
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
    private fun switchAlt(message: ChatMessage, dir: Int) {
        if (generating) return
        messageMutations.switchAlternative(message, dir)
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

    private fun updateMessage(message: ChatMessage, content: String) {
        if (generating) return
        messageMutations.updateMessage(message, content)
    }

    private fun replaceFrontendVariables(scope: String, values: Map<String, String>) {
        val sessionId = if (scope == "global") null else (session?.id ?: return)
        viewModelScope.launch {
            try {
                variableWriteMutex.withLock {
                    if (sessionId == null) {
                        generationDependencies.generationResources.saveGlobalVariables(values)
                        globalVariables = values
                    } else {
                        chatRepository.saveLocalVariables(sessionId, values)
                        conversation.updateCurrent(sessionId) { it.copy(localVariables = values) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                handleOperationFailure(error)
            }
        }
    }

    suspend fun frontendRpc(method: String, paramsJson: String): String =
        when (method) {
            "generation.list-models" -> frontendGeneration.models()
            "generation.call" -> frontendGeneration.generate(
                runCatching { JSONObject(paramsJson) }.getOrElse { JSONObject() },
            )
            "generation.stop" -> frontendGeneration.stop(
                runCatching { JSONObject(paramsJson).optString("generation_id") }.getOrNull(),
            )
            else -> frontendRpcController.call(method, paramsJson)
        }

    private fun emitFrontendEvent(type: String, payloadJson: String = "{}") {
        _frontendEvents.tryEmit(ChatFrontendEvent(frontendEventSequence.incrementAndGet(), type, payloadJson))
    }

}
