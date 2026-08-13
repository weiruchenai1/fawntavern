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
    @ColumnInfo(defaultValue = "") val filesJson: String = "",   // List<MsgFile> JSON，空串 = 无文件附件
    @ColumnInfo(defaultValue = "") val searchJson: String = "",  // List<MsgSearch> JSON，空串 = 无联网搜索
    @ColumnInfo(defaultValue = "0") val promptTokens: Int = 0,
    @ColumnInfo(defaultValue = "0") val completionTokens: Int = 0,
    @ColumnInfo(defaultValue = "0") val generationMs: Long = 0,
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

    @Query("SELECT imagesJson, filesJson FROM messages WHERE imagesJson != '' OR filesJson != ''")
    suspend fun listAttachmentColumns(): List<AttachmentColumns>

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun countSessions(): Int

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

    /** 生成收尾单独回写会话的世界书定时状态（不整会话覆盖，避免踩到分页在改的消息行） */
    @Query("UPDATE sessions SET timedWiJson = :json, updatedAt = :t WHERE id = :id")
    suspend fun updateTimedWi(id: String, json: String, t: Long)

    @Query("UPDATE sessions SET localVariablesJson = :json, updatedAt = :t WHERE id = :id")
    suspend fun updateLocalVariables(id: String, json: String, t: Long)

    /** 整会话覆盖保存。REPLACE 会话行会级联删掉旧消息，再补一次显式删除兜底 */
    @Transaction
    suspend fun saveSession(s: SessionEntity, ms: List<MessageEntity>) {
        insertSession(s)
        deleteMessages(s.id)
        insertMessages(ms)
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

@Database(entities = [SessionEntity::class, MessageEntity::class], version = 9, exportSchema = false)
internal abstract class ChatDatabase : RoomDatabase() {

    abstract fun dao(): ChatDao

    companion object {
        const val NAME = "chats.db"

        /** v2：消息表增加图片/文件附件列 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imagesJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN filesJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v3：会话表增加世界书定时效果状态列 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN timedWiJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v4：会话表增加扩展会话级状态列 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN extStateJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v5：会话表增加标题列 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN title TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v6：消息表增加联网搜索引用列 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN searchJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v7：会话表增加置顶列 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v8：消息表增加 token 用量与生成总耗时 */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN promptTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN completionTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN generationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN localVariablesJson TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, NAME)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                )
                .build().also { instance = it }
        }
    }
}
