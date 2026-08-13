package me.rerere.fawntavern.domain

import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.chat.MsgAlt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationOpsTest {
    @Test
    fun switchingAltPersistsCurrentMirrorBeforeMoving() {
        val message = ChatMessage(
            role = "assistant",
            content = "edited",
            altIdx = 0,
            alts = listOf(MsgAlt(content = "original"), MsgAlt(content = "second")),
        )

        val switched = ConversationOps.switchAltOne(message, 1)

        requireNotNull(switched)
        assertEquals(1, switched.altIdx)
        assertEquals("edited", switched.alts[0].content)
        assertEquals("second", switched.content)
    }

    @Test
    fun deleteAltSelectsNearestVersionAndCollapsesLastVersion() {
        val message = ChatMessage(
            role = "assistant",
            content = "second",
            altIdx = 1,
            alts = listOf(MsgAlt(content = "first"), MsgAlt(content = "second"), MsgAlt(content = "third")),
        )

        val afterDelete = ConversationOps.deleteAltOne(message)
        requireNotNull(afterDelete)
        assertEquals(1, afterDelete.altIdx)
        assertEquals("third", afterDelete.content)

        val collapsed = ConversationOps.deleteAltOne(
            afterDelete.copy(altIdx = 0, content = "first", alts = listOf(MsgAlt(content = "first"), MsgAlt(content = "third"))),
        )
        requireNotNull(collapsed)
        assertEquals(emptyList<MsgAlt>(), collapsed.alts)
        assertEquals(0, collapsed.altIdx)
    }

    @Test
    fun switchingAtBoundaryReturnsNoMutation() {
        val message = ChatMessage(
            role = "assistant",
            content = "only",
            alts = listOf(MsgAlt(content = "only"), MsgAlt(content = "other")),
        )

        assertNull(ConversationOps.switchAltOne(message, -1))
        assertNull(ConversationOps.switchAltOne(message.copy(altIdx = 1, content = "other"), 1))
    }

    @Test
    fun nextTsAlwaysFollowsExistingMessages() {
        val session = ChatSession(messages = listOf(ChatMessage(role = "user", ts = 100L)))

        assertEquals(true, ConversationOps.nextTs(session) > 100L)
    }
}
