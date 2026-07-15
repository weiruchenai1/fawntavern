package me.rerere.stapp.ui.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.stapp.R
import me.rerere.stapp.data.api.ApiConfigStore
import me.rerere.stapp.data.api.ApiProvider
import me.rerere.stapp.data.character.CharRegex
import me.rerere.stapp.data.character.CharacterCard
import me.rerere.stapp.data.character.CharacterRepository
import me.rerere.stapp.data.chat.AttachmentStore
import me.rerere.stapp.data.chat.ChatRepository
import me.rerere.stapp.data.chat.ChatSession
import me.rerere.stapp.data.chat.MsgFile
import me.rerere.stapp.data.preset.PresetRepository
import me.rerere.stapp.data.preset.StPreset
import me.rerere.stapp.data.preset.toCharRegex
import me.rerere.stapp.data.settings.UserProfileStore
import me.rerere.stapp.data.worldbook.WorldBook
import me.rerere.stapp.data.worldbook.WorldBookRepository
import me.rerere.stapp.domain.ConversationOps
import me.rerere.stapp.domain.GenerationController
import me.rerere.stapp.domain.PromptBuilder

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
    var sessions by mutableStateOf<List<ChatSession>>(emptyList()); private set
    var session by mutableStateOf<ChatSession?>(null); private set
    var currentCard by mutableStateOf<CharacterCard?>(null); private set
    /** 当前角色卡的图片（无图为 null，UI 回退到占位图标） */
    var charImageBitmap by mutableStateOf<Bitmap?>(null); private set
    var generating by mutableStateOf(false); private set
    var userName by mutableStateOf(UserProfileStore.getName(app)); private set
    var userAvatarBitmap by mutableStateOf<Bitmap?>(null); private set
    // 当前角色关联的世界书/预设（随角色卡切换加载，生成时进入 Prompt 拼装）
    var activeWorldBooks by mutableStateOf<List<WorldBook>>(emptyList()); private set
    var activePreset by mutableStateOf<StPreset?>(null); private set
    // 独立导入的正则脚本（regex/ 目录），与角色卡内嵌正则一起作用于显示与发送
    var globalRegex by mutableStateOf<List<CharRegex>>(emptyList()); private set
    // 输入草稿放这里，Activity 重建后不丢
    var inputText by mutableStateOf("")
    var attachments by mutableStateOf(listOf<Attachment>())

    /** 显示侧正则：角色卡内嵌 + 独立导入 */
    val displayRegexScripts: List<CharRegex>
        get() = (currentCard?.regexScripts ?: emptyList()) + globalRegex

    private val generation = GenerationController()
    private val ctx: Application get() = getApplication()

    init {
        // 会话列表来自 Repository 的 Flow：任何 save/delete/clear 后自动刷新
        viewModelScope.launch {
            // 每次启动兜底：确保内置"默认角色"卡存在（可编辑、不可删除），并把无角色卡
            // （charFile 为空）的会话归入该卡（幂等）——先于会话收集执行，避免拿到迁移前的旧行
            val defaultName = CharacterRepository.ensureDefaultCard(ctx, ctx.getString(R.string.default_character))
            ChatRepository.migrateCharFile(ctx, "", defaultName)
            // 没有任何会话（全新安装/清空聊天后）：直接落在默认角色上（内存态，发消息才落盘）
            if (ChatRepository.list(ctx).isEmpty()) {
                session = ConversationOps.newSession(loadCard(defaultName), defaultName, defaultName, userName)
            }
            ChatRepository.sessionsFlow(ctx).collect {
                sessions = it
                if (session == null) session = it.firstOrNull()
            }
        }
        // 会话的角色变化时加载对应角色卡（snapshotFlow 值不变不重发）
        viewModelScope.launch {
            snapshotFlow { session?.charFile }.collect { file ->
                currentCard = if (file.isNullOrBlank()) null else loadCard(file)
                charImageBitmap = if (file.isNullOrBlank()) null else loadCharImage(file)
                loadPromptData()
            }
        }
        viewModelScope.launch { reloadGlobalRegex() }
        reloadUserProfile()
    }

    // ── 配置 / 用户资料 ──

    /** 从 API 配置页返回时刷新 */
    fun reloadApiConfig() {
        apiConfig = ApiConfigStore.loadConfig(ctx)
    }

    fun selectModel(providerId: String, modelId: String) {
        apiConfig = apiConfig.copy(currentModel = "$providerId::$modelId")
        ApiConfigStore.saveConfig(ctx, apiConfig)
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

    /** 从世界书/预设页返回或数据管理后刷新：关联内容与独立正则可能已被增删改 */
    fun reloadPromptData() {
        viewModelScope.launch {
            loadPromptData()
            reloadGlobalRegex()
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

    private suspend fun reloadGlobalRegex() {
        globalRegex = PresetRepository.listRegexNames(ctx).mapNotNull { name ->
            try { PresetRepository.loadRegex(ctx, name).toCharRegex() } catch (_: Exception) { null }
        }
    }

    fun currentProviderAndModel(): Pair<ApiProvider, String>? {
        val prov = apiConfig.providers.find { it.id == apiConfig.currentModel.substringBefore("::") }
        val modelId = apiConfig.currentModel.substringAfter("::", "")
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
            // 世界书/预设/独立正则也可能被清空
            loadPromptData()
            reloadGlobalRegex()
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
        viewModelScope.launch {
            // 附件先拷入自有目录（content URI 的读权限是临时的，落盘副本才能支撑历史展示与重答）
            val images = mutableListOf<String>()
            val files = mutableListOf<MsgFile>()
            for (a in atts) {
                if (a.isImage) AttachmentStore.persistImage(ctx, a.uri)?.let { images.add(it) }
                else AttachmentStore.persistFile(ctx, a.uri)?.let { files.add(it) }
            }
            val base = ConversationOps.appendUserMessage(session ?: ChatSession(), text, images, files)
            session = base
            // 用户消息先落盘：生成中途 App 被杀/Activity 重建也不丢消息（生成结束后会再存完整内容）
            ChatRepository.save(ctx, base)
            startGenerate(base, prov, modelId)
        }
        return SendOutcome.STARTED
    }

    fun stopGenerate() {
        generation.stop()
    }

    /** AI 消息重答：保留旧版本，新回复作为新版本（可左右切换）；其后的消息收纳进旧版本的分支下文 */
    fun regenerateAi(idx: Int): SendOutcome {
        if (generating) return SendOutcome.SKIPPED
        val s = session ?: return SendOutcome.SKIPPED
        if (s.messages.getOrNull(idx)?.role != "assistant") return SendOutcome.SKIPPED
        if (s.messages.take(idx).none { it.role == "user" }) return SendOutcome.SKIPPED  // 开场白不可重答
        val (prov, modelId) = currentProviderAndModel() ?: return SendOutcome.NO_MODEL
        val base = ConversationOps.truncateForRegenerate(s, idx)
        session = base
        startGenerate(base, prov, modelId, variantOfLast = true)
        return SendOutcome.STARTED
    }

    /** 用户消息重答：对其后的 AI 回复生成新版本 */
    fun regenerateAfterUser(idx: Int): SendOutcome {
        if (generating) return SendOutcome.SKIPPED
        val s = session ?: return SendOutcome.SKIPPED
        if (s.messages.getOrNull(idx + 1)?.role == "assistant") {
            return regenerateAi(idx + 1)
        }
        val (prov, modelId) = currentProviderAndModel() ?: return SendOutcome.NO_MODEL
        // 走到这里说明其后没有 AI 回复（正常对话流总是有）：极罕见的用户消息连排场景，
        // 没有分支点可挂下文，直接截断
        val base = s.copy(messages = s.messages.take(idx + 1), updatedAt = System.currentTimeMillis())
        session = base
        startGenerate(base, prov, modelId)
        return SendOutcome.STARTED
    }

    private fun startGenerate(base: ChatSession, prov: ApiProvider, modelId: String, variantOfLast: Boolean = false) {
        viewModelScope.launch {
            generating = true
            val built = PromptBuilder.build(
                card = currentCard,
                userName = userName,
                worldBooks = activeWorldBooks,
                preset = activePreset,
                history = base.messages,
                promptRegex = (currentCard?.regexScripts ?: emptyList()) + globalRegex,
            )
            val final = generation.run(
                base = base,
                provider = prov,
                modelId = modelId,
                built = built,
                filesDir = ctx.filesDir,
                streaming = currentCard?.streaming ?: true,
                variantOfLast = variantOfLast,
                errorText = { e -> ctx.getString(R.string.chat_error_fmt, e.message ?: "") },
                onUpdate = { session = it },
            )
            ChatRepository.save(ctx, final)
            generating = false
        }
    }

    // ── 消息操作 ──

    /** 左右切换消息版本；实际发生切换才返回 true（供 UI 决定是否重新锚定滚动位置） */
    fun switchAlt(idx: Int, dir: Int): Boolean {
        if (generating) return false
        val s = session ?: return false
        val upd = ConversationOps.switchAlt(s, idx, dir) ?: return false
        updateSession(upd)
        return true
    }

    /** 删除消息：多分支时只删当前显示的分支，单分支删除整条 */
    fun deleteMessage(idx: Int) {
        if (generating) return
        val s = session ?: return
        updateSession(ConversationOps.deleteMessage(s, idx) ?: return)
    }

    fun updateMessage(idx: Int, content: String) {
        val s = session ?: return
        updateSession(ConversationOps.editMessage(s, idx, content) ?: return)
    }

    private fun updateSession(upd: ChatSession) {
        session = upd
        viewModelScope.launch { ChatRepository.save(ctx, upd) }
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
