package me.rerere.fawntavern.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.fawntavern.ui.hooks.ImeLazyListAutoScroller
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real measured LazyColumn; deterministic IME sizes avoid device-keyboard timing assumptions. */
class ChatScrollInteractionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var controller: ChatScrollController
    private val keyboardHeight = mutableIntStateOf(0)
    private val lastHeight = mutableIntStateOf(72)
    private val generating = mutableStateOf(false)

    @Test
    fun keyboardOpeningAndClosingKeepTheBottomAnchored() {
        showList()
        compose.runOnIdle { controller.scrollToBottom() }
        assertAtBottom()
        compose.runOnIdle { keyboardHeight.intValue = 160 }
        assertAtBottom()
        compose.runOnIdle { keyboardHeight.intValue = 80 }
        assertAtBottom()
        compose.runOnIdle { keyboardHeight.intValue = 0 }
        assertAtBottom()
    }

    @Test
    fun keyboardDoesNotMoveTheHistoryReadingPosition() {
        showList()
        compose.onNodeWithTag("scroll_test_list").performScrollToIndex(10)
        val before = position()
        compose.runOnIdle { keyboardHeight.intValue = 160 }
        assertEquals(before, position())
        compose.runOnIdle { keyboardHeight.intValue = 0 }
        assertEquals(before, position())
    }

    @Test
    fun draggingUpStopsStreamingFollowUntilReturningToBottom() {
        showList()
        compose.runOnIdle {
            controller.scrollToBottom()
            generating.value = true
        }
        assertAtBottom()
        compose.onNodeWithTag("scroll_test_list").performTouchInput { swipeDown() }
        compose.runOnIdle { assertFalse(controller.autoFollow) }
        val before = position()
        compose.runOnIdle { lastHeight.intValue = 240 }
        assertEquals(before, position())
        compose.runOnIdle { controller.scrollToBottom() }
        assertAtBottom()
        compose.runOnIdle { lastHeight.intValue = 320 }
        assertAtBottom()
    }

    @Test
    fun switchingALongMiddleAlternativeKeepsTheNextMessageAnchored() {
        val expanded = mutableStateOf(true)
        compose.setContent {
            controller = rememberChatScrollController()
            controller.inputs.messageCount = 30
            controller.inputs.hasMessages = true
            LazyColumn(Modifier.height(400.dp).testTag("scroll_test_list"), state = controller.listState) {
                items(30, key = { it }) { index ->
                    Text("row $index", Modifier.height(if (index == 10 && expanded.value) 240.dp else 72.dp))
                }
                item { Spacer(Modifier.height(1.dp)) }
            }
        }
        compose.onNodeWithTag("scroll_test_list").performScrollToIndex(10)
        val before = compose.onNodeWithText("row 11").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { controller.switchAnchored(10, false) { expanded.value = false } }
        assertEquals(before, compose.onNodeWithText("row 11").fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    private fun showList() {
        compose.setContent {
            controller = rememberChatScrollController()
            controller.inputs.apply {
                messageCount = 30
                hasMessages = true
                generatingAtEnd = generating.value
            }
            LaunchedEffect(controller) { controller.runLoops() }
            ImeLazyListAutoScroller(
                lazyListState = controller.listState,
                shouldFollow = controller::isAtBottom,
                onFollow = controller::snapToBottom,
                imeInsets = WindowInsets(bottom = keyboardHeight.intValue),
            )
            LazyColumn(
                Modifier.height((400 - keyboardHeight.intValue).dp).testTag("scroll_test_list"),
                state = controller.listState,
            ) {
                items(30, key = { it }) { index ->
                    Text("row $index", Modifier.height(if (index == 29) lastHeight.intValue.dp else 72.dp))
                }
                item { Spacer(Modifier.height(1.dp)) }
            }
        }
        compose.waitForIdle()
    }

    private fun assertAtBottom() {
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("The final anchor must remain visible", controller.listState.canScrollForward) }
    }

    private fun position(): Pair<Int, Int> {
        compose.waitForIdle()
        return compose.runOnIdle { controller.listState.firstVisibleItemIndex to controller.listState.firstVisibleItemScrollOffset }
    }
}
