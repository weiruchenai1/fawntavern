package me.rerere.fawntavern.data.chat

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatDatabaseMigrationTest {
    private lateinit var context: Context
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseFile = context.getDatabasePath(TEST_DATABASE)
        databaseFile.delete()
    }

    @After
    fun tearDown() {
        databaseFile.delete()
        File(databaseFile.path + "-shm").delete()
        File(databaseFile.path + "-wal").delete()
    }

    @Test
    fun migration9To10PreservesSessionsAndMessages() {
        createVersion9Database().use { helper ->
            helper.writableDatabase.apply {
                execSQL(
                    "INSERT INTO sessions " +
                        "(id, charFile, charName, createdAt, updatedAt, title) " +
                        "VALUES ('session-1', 'fawn.json', 'Fawn', 1, 2, 'Existing chat')"
                )
                execSQL(
                    "INSERT INTO messages " +
                        "(sessionId, ts, role, content, reasoning, model, reasoningMs, altIdx, altsJson) " +
                        "VALUES ('session-1', 3, 'user', 'Keep me', '', '', 0, 0, '')"
                )
            }
        }

        val migrated = Room.databaseBuilder(context, ChatDatabase::class.java, TEST_DATABASE)
            .addMigrations(ChatDatabase.MIGRATION_9_10)
            .allowMainThreadQueries()
            .build()
        try {
            val session = runBlocking { migrated.dao().getSession("session-1") }
            assertEquals("Existing chat", session?.session?.title)
            assertEquals(listOf("Keep me"), session?.messages?.map(MessageEntity::content))
        } finally {
            migrated.close()
        }
    }

    private fun createVersion9Database(): SupportSQLiteOpenHelper {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DATABASE)
            .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(SESSIONS_SQL)
                    db.execSQL(MESSAGES_SQL)
                    db.execSQL(MESSAGES_INDEX_SQL)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration)
    }

    private companion object {
        const val TEST_DATABASE = "migration-test.db"
        const val SESSIONS_SQL = """
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT NOT NULL PRIMARY KEY,
                charFile TEXT NOT NULL,
                charName TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                localVariablesJson TEXT NOT NULL DEFAULT '',
                timedWiJson TEXT NOT NULL DEFAULT '',
                extStateJson TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '',
                pinned INTEGER NOT NULL DEFAULT 0
            )
        """
        const val MESSAGES_SQL = """
            CREATE TABLE IF NOT EXISTS messages (
                sessionId TEXT NOT NULL,
                ts INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                reasoning TEXT NOT NULL,
                model TEXT NOT NULL,
                reasoningMs INTEGER NOT NULL,
                altIdx INTEGER NOT NULL,
                altsJson TEXT NOT NULL,
                imagesJson TEXT NOT NULL DEFAULT '',
                filesJson TEXT NOT NULL DEFAULT '',
                searchJson TEXT NOT NULL DEFAULT '',
                promptTokens INTEGER NOT NULL DEFAULT 0,
                completionTokens INTEGER NOT NULL DEFAULT 0,
                generationMs INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(sessionId, ts),
                FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """
        const val MESSAGES_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS index_messages_sessionId ON messages(sessionId)"
    }
}
