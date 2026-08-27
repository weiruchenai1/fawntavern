package me.rerere.fawntavern.data.chat

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executors
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatDaoTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.dao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun searchReturnsOneEarliestHitPerSession() = runBlocking {
        dao.insertSession(session("s1", title = ""))
        dao.upsertMessage(message("s1", 1, "user", "first title"))
        dao.upsertMessage(message("s1", 2, "assistant", "fawn first hit"))
        dao.upsertMessage(message("s1", 3, "assistant", "fawn second hit"))

        val results = dao.searchMessages("char.json", "FAWN", 100)

        assertEquals(1, results.size)
        assertEquals("first title", results.single().title)
        assertEquals("fawn first hit", results.single().content)
    }

    @Test
    fun concurrentMessageWritesRemainDistinct() = runBlocking {
        dao.insertSession(session("s1"))
        val executor = Executors.newFixedThreadPool(4)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            (1L..40L).map { ts ->
                async(dispatcher) {
                    dao.upsertMessage(message("s1", ts, "user", "message-$ts"))
                }
            }.awaitAll()
        } finally {
            dispatcher.close()
            executor.shutdown()
        }

        assertEquals(40, dao.countMessages("s1"))
    }

    @Test
    fun pagingSourceReturnsMessagesInTimestampOrder() = runBlocking {
        dao.insertSession(session("s1"))
        dao.upsertMessage(message("s1", 30, "user", "third"))
        dao.upsertMessage(message("s1", 10, "user", "first"))
        dao.upsertMessage(message("s1", 20, "user", "second"))

        val page = dao.messagesPaged("s1").load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page<Int, MessageEntity>

        assertEquals(listOf(10L, 20L, 30L), page.data.map { it.ts })
    }

    @Test
    fun failedBatchRestoreRollsBackEverySession() = runBlocking {
        dao.saveSession(
            session("existing", title = "before"),
            listOf(message("existing", 1, "user", "original")),
        )

        val failure = runCatching {
            dao.restoreSessions(
                sessions = listOf(
                    session("existing", title = "overwritten"),
                    session("new", title = "new"),
                ),
                messagesBySession = listOf(
                    listOf(message("existing", 2, "user", "replacement")),
                    listOf(message("missing-parent", 1, "user", "invalid")),
                ),
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        val existing = dao.getSession("existing")
        assertEquals("before", existing?.session?.title)
        assertEquals(listOf("original"), existing?.messages?.map { it.content })
        assertNull(dao.getSession("new"))
        assertTrue(dao.countSessions() == 1)
    }

    @Test
    fun replaceAllSessionsRestoresExactSnapshot() = runBlocking {
        dao.saveSession(
            session("old", title = "remove me"),
            listOf(message("old", 1, "user", "stale")),
        )

        dao.replaceAllSessions(
            sessions = listOf(session("snapshot", title = "restored")),
            messagesBySession = listOf(listOf(message("snapshot", 2, "assistant", "saved"))),
        )

        assertNull(dao.getSession("old"))
        val restored = dao.getSession("snapshot")
        assertEquals("restored", restored?.session?.title)
        assertEquals(listOf("saved"), restored?.messages?.map { it.content })
        assertEquals(1, dao.countSessions())
    }

    private fun session(id: String, title: String = "title") = SessionEntity(
        id = id,
        charFile = "char.json",
        charName = "Fawn",
        createdAt = 1,
        updatedAt = 1,
        title = title,
    )

    private fun message(sessionId: String, ts: Long, role: String, content: String) = MessageEntity(
        sessionId = sessionId,
        ts = ts,
        role = role,
        content = content,
        reasoning = "",
        model = "",
        reasoningMs = 0,
        altIdx = 0,
        altsJson = "",
    )
}
