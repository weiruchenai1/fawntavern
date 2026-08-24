package me.rerere.fawntavern.data.chat

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
internal data class SessionEntity(
    @PrimaryKey val id: String,
    val charFile: String,
    val charName: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "") val localVariablesJson: String = "",
    @ColumnInfo(defaultValue = "") val timedWiJson: String = "",  // 世界书定时效果状态 JSON
    @ColumnInfo(defaultValue = "") val extStateJson: String = "",  // 每扩展会话级状态 JSON（extId → blob）
    @ColumnInfo(defaultValue = "") val title: String = "",  // 会话标题（标题模型自动生成）
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,  // 是否固定在抽屉列表顶部
)

/** (sessionId, ts) 为主键：ts 在会话内严格递增，天然唯一且保序 */
@Entity(
    tableName = "messages",
    primaryKeys = ["sessionId", "ts"],
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"], childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
internal data class MessageEntity(
    val sessionId: String,
    val ts: Long,
    val role: String,
    val content: String,
    val reasoning: String,
    val model: String,
    val reasoningMs: Long,
    val altIdx: Int,
    val altsJson: String,  // List<MsgAlt> 的 kotlinx.serialization JSON，空串 = 无多版本
    @ColumnInfo(defaultValue = "") val imagesJson: String = "",  // List<String> JSON，空串 = 无图片附件
    @ColumnInfo(defaultValue = "'2:3'") val imageAspectRatio: String = "2:3",
    @ColumnInfo(defaultValue = "") val filesJson: String = "",   // List<MsgFile> JSON，空串 = 无文件附件
    @ColumnInfo(defaultValue = "") val searchJson: String = "",  // List<MsgSearch> JSON，空串 = 无联网搜索
    @ColumnInfo(defaultValue = "0") val promptTokens: Int = 0,
    @ColumnInfo(defaultValue = "0") val completionTokens: Int = 0,
    @ColumnInfo(defaultValue = "0") val cachedTokens: Int = 0,
    @ColumnInfo(defaultValue = "0") val generationMs: Long = 0,
    @ColumnInfo(defaultValue = "") val requestSnapshotsJson: String = "",
)

internal data class SessionWithMessages(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val messages: List<MessageEntity>,
)

internal data class SessionSummaryRow(
    @Embedded val session: SessionEntity,
    val preview: String?,
)

internal data class ChatSearchRow(
    val sessionId: String,
    val title: String,
    val content: String,
)

internal data class AttachmentColumns(
    val imagesJson: String,
    val filesJson: String,
    val altsJson: String,
)

internal data class TokenStatsRow(
    val totalMessages: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

internal data class DailyMessageCountRow(
    val day: String,
    val count: Int,
)

@Dao
internal interface ChatDao {

    @Query("""
        SELECT sessions.*,
            (SELECT content FROM messages
             WHERE messages.sessionId = sessions.id AND messages.role = 'user'
             ORDER BY messages.ts ASC LIMIT 1) AS preview
        FROM sessions
        ORDER BY pinned DESC, updatedAt DESC
    """)
    fun observeSummaries(): Flow<List<SessionSummaryRow>>

    @Query("""
        SELECT sessions.*,
            (SELECT content FROM messages
             WHERE messages.sessionId = sessions.id AND messages.role = 'user'
             ORDER BY messages.ts ASC LIMIT 1) AS preview
        FROM sessions
        ORDER BY pinned DESC, updatedAt DESC
    """)
    suspend fun listSummaries(): List<SessionSummaryRow>

    /** 每个会话只返回最早的一条命中消息，避免搜索页把完整聊天历史载入内存。 */
    @Query("""
        SELECT s.id AS sessionId,
            CASE WHEN s.title != '' THEN s.title ELSE COALESCE(
                (SELECT content FROM messages p
                 WHERE p.sessionId = s.id AND p.role = 'user'
                 ORDER BY p.ts ASC LIMIT 1), '') END AS title,
            m.content AS content
        FROM sessions s
        JOIN messages m ON m.sessionId = s.id
        WHERE s.charFile = :charFile
          AND instr(lower(m.content), lower(:query)) > 0
          AND m.ts = (
              SELECT MIN(hit.ts) FROM messages hit
              WHERE hit.sessionId = s.id
                AND instr(lower(hit.content), lower(:query)) > 0
          )
        ORDER BY s.updatedAt DESC
        LIMIT :limit
    """)
    suspend fun searchMessages(charFile: String, query: String, limit: Int): List<ChatSearchRow>

    @Query("SELECT imagesJson, filesJson, altsJson FROM messages WHERE imagesJson != '' OR filesJson != '' OR altsJson != ''")
    suspend fun listAttachmentColumns(): List<AttachmentColumns>

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun countSessions(): Int

    @Query("SELECT COUNT(*) AS totalMessages, COALESCE(SUM(promptTokens), 0) AS promptTokens, COALESCE(SUM(completionTokens), 0) AS completionTokens, COALESCE(SUM(cachedTokens), 0) AS cachedTokens FROM messages")
    suspend fun tokenStats(): TokenStatsRow

    @Query("SELECT strftime('%Y-%m-%d', ts / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count FROM messages WHERE role = 'user' AND ts >= :startMillis GROUP BY day ORDER BY day")
    suspend fun messageCountPerDay(startMillis: Long): List<DailyMessageCountRow>

    @Transaction
    @Query("SELECT * FROM sessions ORDER BY pinned DESC, updatedAt DESC")
    suspend fun listAll(): List<SessionWithMessages>

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: String): SessionWithMessages?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(s: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(ms: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE sessionId = :id")
    suspend fun deleteMessages(id: String)

    // ── 单条消息的按 (sessionId, ts) 粒度操作（分支切换/删除/编辑走 DB，不再整会话覆盖） ──

    @Query("SELECT * FROM messages WHERE sessionId = :sid AND ts = :ts")
    suspend fun getMessage(sid: String, ts: Long): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(m: MessageEntity)

    @Query("DELETE FROM messages WHERE sessionId = :sid AND ts = :ts")
    suspend fun deleteMessageRow(sid: String, ts: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sid")
    suspend fun countMessages(sid: String): Int

    /** 截断：删掉该会话内 ts 大于给定值的所有消息（用户消息后无 AI 回复时的重答兜底） */
    @Query("DELETE FROM messages WHERE sessionId = :sid AND ts > :ts")
    suspend fun deleteMessagesAfter(sid: String, ts: Long)

    /** 单条消息变更后回填会话 updatedAt（抽屉据此重排序） */
    @Query("UPDATE sessions SET updatedAt = :t WHERE id = :id")
    suspend fun touchSession(id: String, t: Long)

    @Transaction
    suspend fun upsertMessageAndTouch(message: MessageEntity, updatedAt: Long) {
        upsertMessage(message)
        touchSession(message.sessionId, updatedAt)
    }

    /** 生成收尾单独回写会话的世界书定时状态（不整会话覆盖，避免踩到分页在改的消息行） */
    @Query("UPDATE sessions SET timedWiJson = :json, updatedAt = :t WHERE id = :id")
    suspend fun updateTimedWi(id: String, json: String, t: Long)

    @Transaction
    suspend fun commitGeneration(message: MessageEntity, timedWiJson: String, updatedAt: Long) {
        upsertMessage(message)
        updateTimedWi(message.sessionId, timedWiJson, updatedAt)
    }

    @Query("UPDATE sessions SET localVariablesJson = :json, updatedAt = :t WHERE id = :id")
    suspend fun updateLocalVariables(id: String, json: String, t: Long)

    /** 整会话覆盖保存。REPLACE 会话行会级联删掉旧消息，再补一次显式删除兜底 */
    @Transaction
    suspend fun saveSession(s: SessionEntity, ms: List<MessageEntity>) {
        insertSession(s)
        deleteMessages(s.id)
        insertMessages(ms)
    }

    /** 整批恢复共用一个事务；任一会话或消息失败时全部回滚。 */
    @Transaction
    suspend fun restoreSessions(
        sessions: List<SessionEntity>,
        messagesBySession: List<List<MessageEntity>>,
    ) {
        require(sessions.size == messagesBySession.size)
        sessions.indices.forEach { index ->
            saveSession(sessions[index], messagesBySession[index])
        }
    }

    /** 精确恢复完整数据库快照；删除与重建处于同一个 Room 事务中。 */
    @Transaction
    suspend fun replaceAllSessions(
        sessions: List<SessionEntity>,
        messagesBySession: List<List<MessageEntity>>,
    ) {
        require(sessions.size == messagesBySession.size)
        clearSessions()
        sessions.indices.forEach { index ->
            saveSession(sessions[index], messagesBySession[index])
        }
    }

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("DELETE FROM sessions")
    suspend fun clearSessions()

    /** 单会话消息的分页数据源（Paging 3），按 ts 升序 */
    @Query("SELECT * FROM messages WHERE sessionId = :id ORDER BY ts ASC")
    fun messagesPaged(id: String): PagingSource<Int, MessageEntity>

    /** 回写会话标题 */
    @Query("UPDATE sessions SET title = :title, updatedAt = :t WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, t: Long)

    @Query("UPDATE sessions SET pinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: String, pinned: Boolean)
}

@Database(entities = [SessionEntity::class, MessageEntity::class], version = 13, exportSchema = true)
internal abstract class ChatDatabase : RoomDatabase() {

    abstract fun dao(): ChatDao

    companion object {
        const val NAME = "chats.db"

        /** v9 是首个作为公开发布基线维护的数据库结构。 */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imageAspectRatio TEXT NOT NULL DEFAULT '2:3'")
            }
        }

        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN cachedTokens INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN requestSnapshotsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        private val ALL_MIGRATIONS = arrayOf(
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
        )

        @Volatile private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, NAME)
                .addMigrations(*ALL_MIGRATIONS)
                .build().also { instance = it }
        }
    }
}
