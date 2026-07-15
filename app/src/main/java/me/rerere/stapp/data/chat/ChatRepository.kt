package me.rerere.stapp.data.chat

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

/** 聊天会话存储：Room 数据库（sessions + messages 两张表），写入后 Flow 自动重发 */
object ChatRepository {

    // encodeDefaults：alts 里嵌套的 ChatMessage.ts 默认值是 System.currentTimeMillis()，
    // 不强制编码时若恰逢同毫秒会被当作默认值省略，读回来 ts 就变了（ts 是消息主键/列表 key）
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun dao(context: Context): ChatDao = ChatDatabase.get(context).dao()

    /** 全部会话（按更新时间倒序），save/delete/clear 后由 Room 自动重发 */
    fun sessionsFlow(context: Context): Flow<List<ChatSession>> =
        dao(context).observeAll().map { rows -> rows.map { it.toModel() } }

    /** 列出全部会话，按更新时间倒序 */
    suspend fun list(context: Context): List<ChatSession> =
        dao(context).listAll().map { it.toModel() }

    suspend fun save(context: Context, session: ChatSession) {
        dao(context).saveSession(
            s = SessionEntity(
                id = session.id, charFile = session.charFile, charName = session.charName,
                createdAt = session.createdAt, updatedAt = session.updatedAt,
            ),
            ms = session.messages.map { it.toEntity(session.id) },
        )
    }

    suspend fun delete(context: Context, id: String) {
        dao(context).deleteSession(id)
    }

    /** 清空所有会话（附件随之全部失去引用，一并删除） */
    suspend fun clear(context: Context) {
        dao(context).clearSessions()
        withContext(Dispatchers.IO) {
            AttachmentStore.dir(context).listFiles()?.forEach { it.delete() }
        }
    }

    /** 把 charFile 为 [from] 的会话归入 [to]（默认角色卡首次播种时的一次性迁移） */
    suspend fun migrateCharFile(context: Context, from: String, to: String) {
        dao(context).migrateCharFile(from, to)
    }

    /** 单会话消息的分页流（Paging 3），供海量消息场景按需加载 */
    fun messagesPaged(context: Context, sessionId: String): Flow<PagingData<ChatMessage>> =
        Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) {
            dao(context).messagesPaged(sessionId)
        }.flow.map { paging -> paging.map { it.toModel() } }

    /** 数据管理页统计存储占用用：Room 数据库所在目录 */
    fun storageDir(context: Context): File? =
        context.getDatabasePath(ChatDatabase.NAME).parentFile

    private fun SessionWithMessages.toModel() = ChatSession(
        id = session.id,
        charFile = session.charFile,
        charName = session.charName,
        messages = messages.sortedBy { it.ts }.map { it.toModel() },
        createdAt = session.createdAt,
        updatedAt = session.updatedAt,
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
        files = if (filesJson.isBlank()) emptyList()
                else try { json.decodeFromString<List<MsgFile>>(filesJson) } catch (_: Exception) { emptyList() },
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
        filesJson = if (files.isEmpty()) "" else json.encodeToString(files),
    )
}
