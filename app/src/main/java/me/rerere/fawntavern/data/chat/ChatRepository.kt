package me.rerere.fawntavern.data.chat

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.fawntavern.domain.ConversationOps

/** 聊天会话存储：Room 数据库（sessions + messages 两张表），写入后 Flow 自动重发 */
object ChatRepository {

    data class Statistics(
        val totalConversations: Int,
        val totalMessages: Int,
        val promptTokens: Long,
        val completionTokens: Long,
        val cachedTokens: Long,
        val messagesPerDay: Map<String, Int>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun dao(context: Context): ChatDao = ChatDatabase.get(context).dao()

    /** 全部会话（按更新时间倒序），save/delete/clear 后由 Room 自动重发 */
    fun sessionsFlow(context: Context): Flow<List<ChatSession>> =
        dao(context).observeSummaries().map { rows -> rows.map { it.toModel() } }

    /** 会话摘要列表；只查询会话元数据与首条用户消息，不加载完整消息历史。 */
    suspend fun listSummaries(context: Context): List<ChatSession> =
        dao(context).listSummaries().map { it.toModel() }

    suspend fun count(context: Context): Int = dao(context).countSessions()

    suspend fun statistics(context: Context, startMillis: Long): Statistics {
        val d = dao(context)
        val tokens = d.tokenStats()
        return Statistics(
            totalConversations = d.countSessions(),
            totalMessages = tokens.totalMessages,
            promptTokens = tokens.promptTokens,
            completionTokens = tokens.completionTokens,
            cachedTokens = tokens.cachedTokens,
            messagesPerDay = d.messageCountPerDay(startMillis).associate { it.day to it.count },
        )
    }

    data class SearchResult(val sessionId: String, val title: String, val content: String)

    suspend fun searchMessages(
        context: Context,
        charFile: String,
        query: String,
        limit: Int = 100,
    ): List<SearchResult> = dao(context).searchMessages(charFile, query, limit).map {
        SearchResult(it.sessionId, it.title, it.content)
    }

    /** 列出全部会话，按更新时间倒序 */
    suspend fun list(context: Context): List<ChatSession> =
        dao(context).listAll().map { it.toModel() }

    /** 按 id 取单个会话（含消息），不存在返回 null */
    suspend fun get(context: Context, id: String): ChatSession? =
        dao(context).getSession(id)?.toModel()

    suspend fun save(context: Context, session: ChatSession) {
        dao(context).saveSession(s = session.toEntity(),
            ms = session.messages.map { it.toEntity(session.id) },
        )
    }

    /** 原子恢复备份内的会话；同 id 覆盖，任一项失败则整批回滚。 */
    suspend fun restore(context: Context, sessions: List<ChatSession>) {
        ChatDatabase.get(context).dao().restoreSessions(
            sessions = sessions.map { it.toEntity() },
            messagesBySession = sessions.map { session ->
                session.messages.map { it.toEntity(session.id) }
            },
        )
    }

    /** 用完整快照替换数据库内容，供跨存储导入失败时精确回滚。 */
    suspend fun replaceAll(context: Context, sessions: List<ChatSession>) {
        ChatDatabase.get(context).dao().replaceAllSessions(
            sessions = sessions.map { it.toEntity() },
            messagesBySession = sessions.map { session ->
                session.messages.map { it.toEntity(session.id) }
            },
        )
    }

    suspend fun delete(context: Context, id: String) {
        dao(context).deleteSession(id)
        collectUnusedAttachments(context)
    }

    /** 清空所有会话（附件随之全部失去引用，一并删除） */
    suspend fun clear(context: Context) {
        dao(context).clearSessions()
        withContext(Dispatchers.IO) {
            AttachmentStore.dir(context).listFiles()?.forEach { it.delete() }
        }
    }


    /** 单会话消息的分页流（Paging 3），供海量消息场景按需加载。
     *  [initialKey] 为初始加载偏移（一般传 count-pageSize 让最新一页先加载、天然停在底部）。 */
    fun messagesPaged(context: Context, sessionId: String, initialKey: Int? = null): Flow<PagingData<ChatMessage>> =
        Pager(PagingConfig(pageSize = 60, enablePlaceholders = false), initialKey = initialKey) {
            dao(context).messagesPaged(sessionId)
        }.flow.map { paging -> paging.map { it.toModel() } }

    /** 会话消息总数（分页初始定位到底部用） */
    suspend fun messageCount(context: Context, sessionId: String): Int =
        dao(context).countMessages(sessionId)

    /** 取单条消息（不存在返回 null） */
    suspend fun getMessage(context: Context, sessionId: String, ts: Long): ChatMessage? =
        dao(context).getMessage(sessionId, ts)?.toModel()

    /** 写入/覆盖单条消息（生成起止、重答开新版本等），并回填会话 updatedAt */
    suspend fun putMessage(context: Context, sessionId: String, msg: ChatMessage) {
        dao(context).upsertMessageAndTouch(msg.toEntity(sessionId), System.currentTimeMillis())
    }

    /** Commit the final assistant message and its world-info timer state in one Room transaction. */
    suspend fun commitGeneration(
        context: Context,
        sessionId: String,
        msg: ChatMessage,
        timedWi: Map<String, Int>,
    ) {
        dao(context).commitGeneration(
            message = msg.toEntity(sessionId),
            timedWiJson = if (timedWi.isEmpty()) "" else json.encodeToString(timedWi),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 左右切换单条消息版本（DB 落盘）：实际发生切换返回 true */
    suspend fun switchAlt(context: Context, sessionId: String, ts: Long, dir: Int): Boolean {
        val d = dao(context)
        val m = d.getMessage(sessionId, ts)?.toModel() ?: return false
        val upd = ConversationOps.switchAltOne(m, dir) ?: return false
        d.upsertMessage(upd.toEntity(sessionId))
        d.touchSession(sessionId, System.currentTimeMillis())
        return true
    }

    /** 删除单条消息（DB 落盘）：多版本删当前版本，单版本删整条 */
    suspend fun deleteMessage(context: Context, sessionId: String, ts: Long) {
        val d = dao(context)
        val m = d.getMessage(sessionId, ts)?.toModel() ?: return
        val upd = ConversationOps.deleteAltOne(m)
        if (upd == null) d.deleteMessageRow(sessionId, ts)
        else d.upsertMessage(upd.toEntity(sessionId))
        d.touchSession(sessionId, System.currentTimeMillis())
        collectUnusedAttachments(context)
    }

    /** 删除整条消息（全部版本） */
    suspend fun deleteAllVersions(context: Context, sessionId: String, ts: Long) {
        val d = dao(context)
        d.deleteMessageRow(sessionId, ts)
        d.touchSession(sessionId, System.currentTimeMillis())
        collectUnusedAttachments(context)
    }

    /** 编辑单条消息正文（DB 落盘） */
    suspend fun editMessage(context: Context, sessionId: String, ts: Long, content: String) {
        val d = dao(context)
        val m = d.getMessage(sessionId, ts)?.toModel() ?: return
        d.upsertMessage(m.copy(content = content).toEntity(sessionId))
        d.touchSession(sessionId, System.currentTimeMillis())
    }

    /** 截断该会话内 ts 之后的所有消息 */
    suspend fun truncateAfter(context: Context, sessionId: String, ts: Long) {
        val d = dao(context)
        d.deleteMessagesAfter(sessionId, ts)
        d.touchSession(sessionId, System.currentTimeMillis())
        collectUnusedAttachments(context)
    }

    /** 单独回写会话的世界书定时状态（生成收尾用，不整会话覆盖） */
    suspend fun saveTimedWi(context: Context, sessionId: String, timedWi: Map<String, Int>) {
        dao(context).updateTimedWi(
            sessionId,
            if (timedWi.isEmpty()) "" else json.encodeToString(timedWi),
            System.currentTimeMillis(),
        )
    }

    /** 数据管理页统计存储占用用：Room 数据库所在目录 */
    fun storageDir(context: Context): File? =
        context.getDatabasePath(ChatDatabase.NAME).parentFile

    /** 回写会话标题（标题模型自动命名用） */
    suspend fun updateTitle(context: Context, sessionId: String, title: String) {
        dao(context).updateTitle(sessionId, title, System.currentTimeMillis())
    }

    suspend fun updatePinned(context: Context, sessionId: String, pinned: Boolean) {
        dao(context).updatePinned(sessionId, pinned)
    }

    suspend fun saveLocalVariables(context: Context, sessionId: String, variables: Map<String, String>) {
        dao(context).updateLocalVariables(
            sessionId,
            if (variables.isEmpty()) "" else json.encodeToString(variables),
            System.currentTimeMillis(),
        )
    }

    /** 删除已不被任何消息引用的附件；导入、删消息和删会话后均可安全调用。 */
    suspend fun collectUnusedAttachments(context: Context) {
        val referenced = dao(context).listAttachmentColumns().asSequence().flatMap { row ->
            val images = if (row.imagesJson.isBlank()) emptyList() else
                runCatching { json.decodeFromString<List<String>>(row.imagesJson) }.getOrDefault(emptyList())
            val altImages = if (row.altsJson.isBlank()) emptyList() else
                runCatching { json.decodeFromString<List<MsgAlt>>(row.altsJson) }
                    .getOrDefault(emptyList())
                    .flatMap { it.images }
            val files = if (row.filesJson.isBlank()) emptyList() else
                runCatching { json.decodeFromString<List<MsgFile>>(row.filesJson) }.getOrDefault(emptyList())
                    .map { it.path }
            (images + altImages + files).asSequence()
        }.map { it.substringAfterLast('/') }.toSet()
        withContext(Dispatchers.IO) {
            AttachmentStore.dir(context).listFiles()?.forEach { file ->
                if (file.isFile && file.name !in referenced) file.delete()
            }
        }
    }

    private fun SessionSummaryRow.toModel() = ChatSession(
        id = session.id,
        charFile = session.charFile,
        charName = session.charName,
        messages = preview?.let { listOf(ChatMessage(role = "user", content = it)) } ?: emptyList(),
        createdAt = session.createdAt,
        updatedAt = session.updatedAt,
        title = session.title,
        pinned = session.pinned,
    )

    private fun ChatSession.toEntity() = SessionEntity(
        localVariablesJson = if (localVariables.isEmpty()) "" else json.encodeToString(localVariables),
        id = id,
        charFile = charFile,
        charName = charName,
        createdAt = createdAt,
        updatedAt = updatedAt,
        timedWiJson = if (timedWi.isEmpty()) "" else json.encodeToString(timedWi),
        extStateJson = if (extState.isEmpty()) "" else json.encodeToString(extState),
        title = title,
        pinned = pinned,
    )

    private fun SessionWithMessages.toModel() = ChatSession(
        localVariables = if (session.localVariablesJson.isBlank()) emptyMap()
                         else try { json.decodeFromString<Map<String, String>>(session.localVariablesJson) } catch (_: Exception) { emptyMap() },
        id = session.id,
        charFile = session.charFile,
        charName = session.charName,
        messages = messages.sortedBy { it.ts }.map { it.toModel() },
        createdAt = session.createdAt,
        updatedAt = session.updatedAt,
        timedWi = if (session.timedWiJson.isBlank()) emptyMap()
                  else try { json.decodeFromString<Map<String, Int>>(session.timedWiJson) } catch (_: Exception) { emptyMap() },
        extState = if (session.extStateJson.isBlank()) emptyMap()
                   else try { json.decodeFromString<Map<String, String>>(session.extStateJson) } catch (_: Exception) { emptyMap() },
        title = session.title,
        pinned = session.pinned,
    )

    private fun MessageEntity.toModel() = ChatMessage(
        role = role,
        content = content,
        reasoning = reasoning,
        model = model,
        reasoningMs = reasoningMs,
        ts = ts,
        alts = if (altsJson.isBlank()) emptyList()
               else try { json.decodeFromString<List<MsgAlt>>(altsJson) } catch (_: Exception) { emptyList() },
        altIdx = altIdx,
        images = if (imagesJson.isBlank()) emptyList()
                 else try { json.decodeFromString<List<String>>(imagesJson) } catch (_: Exception) { emptyList() },
        imageAspectRatio = imageAspectRatio,
        files = if (filesJson.isBlank()) emptyList()
                else try { json.decodeFromString<List<MsgFile>>(filesJson) } catch (_: Exception) { emptyList() },
        // 列格式为 List<MsgSearch> JSON；早期开发版存过单对象，解码失败时按单对象兜底
        searches = if (searchJson.isBlank()) emptyList()
                   else try { json.decodeFromString<List<MsgSearch>>(searchJson) } catch (_: Exception) {
                       try { listOf(json.decodeFromString<MsgSearch>(searchJson)) } catch (_: Exception) { emptyList() }
                   },
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = cachedTokens,
        generationMs = generationMs,
    )

    private fun ChatMessage.toEntity(sessionId: String) = MessageEntity(
        sessionId = sessionId,
        ts = ts,
        role = role,
        content = content,
        reasoning = reasoning,
        model = model,
        reasoningMs = reasoningMs,
        altIdx = altIdx,
        altsJson = if (alts.isEmpty()) "" else json.encodeToString(alts),
        imagesJson = if (images.isEmpty()) "" else json.encodeToString(images),
        imageAspectRatio = imageAspectRatio,
        filesJson = if (files.isEmpty()) "" else json.encodeToString(files),
        searchJson = if (searches.isEmpty()) "" else json.encodeToString(searches),
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = cachedTokens,
        generationMs = generationMs,
    )
}
