package me.rerere.fawntavern.data.chat

import me.rerere.fawntavern.data.api.ApiRequestSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageAlternativesTest {
    @Test
    fun switchPersistsTheCurrentMirrorAndLoadsTheTarget() {
        val firstSnapshot = ApiRequestSnapshot(body = "first")
        val secondSnapshot = ApiRequestSnapshot(body = "second")
        val message = ChatMessage(
            role = "assistant",
            content = "edited",
            dataJson = "{\"state\":1}",
            images = listOf("edited.png"),
            requestSnapshots = listOf(firstSnapshot),
            alts = listOf(
                MsgAlt(content = "stale"),
                MsgAlt(
                    content = "target",
                    dataJson = "{\"state\":2}",
                    images = listOf("target.png"),
                    requestSnapshots = listOf(secondSnapshot),
                ),
            ),
        )

        val switched = requireNotNull(MessageAlternatives.switch(message, 1))

        assertEquals(1, switched.altIdx)
        assertEquals("edited", switched.alts[0].content)
        assertEquals("{\"state\":1}", switched.alts[0].dataJson)
        assertEquals(listOf("edited.png"), switched.alts[0].images)
        assertEquals("target", switched.content)
        assertEquals("{\"state\":2}", switched.dataJson)
        assertEquals(listOf(secondSnapshot), switched.requestSnapshots)
    }

    @Test
    fun switchAtBoundaryDoesNotMutate() {
        val message = ChatMessage(
            role = "assistant",
            alts = listOf(MsgAlt(content = "first"), MsgAlt(content = "second")),
        )

        assertNull(MessageAlternatives.switch(message, -1))
    }

    @Test
    fun deleteSelectsTheNearestVersionAndCollapsesTheLastAlternative() {
        val message = ChatMessage(
            role = "assistant",
            content = "second",
            altIdx = 1,
            alts = listOf(
                MsgAlt(content = "first"),
                MsgAlt(content = "second"),
                MsgAlt(content = "third"),
            ),
        )

        val afterDelete = requireNotNull(MessageAlternatives.deleteCurrent(message))
        assertEquals(1, afterDelete.altIdx)
        assertEquals("third", afterDelete.content)

        val collapsed = requireNotNull(
            MessageAlternatives.deleteCurrent(
                afterDelete.copy(
                    altIdx = 0,
                    content = "first",
                    alts = listOf(MsgAlt(content = "first"), MsgAlt(content = "third")),
                ),
            ),
        )
        assertEquals(emptyList<MsgAlt>(), collapsed.alts)
        assertEquals(0, collapsed.altIdx)
        assertEquals("third", collapsed.content)
    }
}
