package me.rerere.fawntavern.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.fawntavern.ui.hooks.ImeLazyListAutoScroller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.junit.rules.TimeoutRule

/** 使用真实列表测量和可控 IME 高度验证锚定，避免依赖设备输入法动画。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalTestApi::class)
class ChatScrollInteractionTest {
    @get:Rule(order = 0) val timeout = TimeoutRule.seconds(90)
    @get:Rule(order = 1) val compose = createComposeRule()
    private lateinit var controller: ChatScrollController
    private val keyboardHeight = mutableIntStateOf(0)
    private val middleHeight = mutableIntStateOf(240)
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
    fun streamingAtBottomBecomesIdleAndStillFollowsNewContent() {
        showList()
        compose.runOnIdle {
            controller.scrollToBottom()
            generating.value = true
        }
        assertAtBottom()
        // 流仍打开但没有新内容时，也必须能结束布局同步。
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        compose.runOnIdle { lastHeight.intValue = 240 }
        assertAtBottom()
        compose.runOnIdle { lastHeight.intValue = 480 }
        assertAtBottom()
    }

    @Test
    fun switchingALongMiddleAlternativeKeepsTheNextMessageAnchored() {
        showList()
        compose.onNodeWithTag("scroll_test_list").performScrollToIndex(10)
        val before = compose.onNodeWithText("row 11").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { controller.switchAnchored(10, false) { middleHeight.intValue = 72 } }
        assertEquals(before, compose.onNodeWithText("row 11").fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    @Test
    fun switchingAShortMiddleAlternativeKeepsTheNextMessageAnchored() {
        middleHeight.intValue = 72
        showList()
        compose.onNodeWithTag("scroll_test_list").performScrollToIndex(10)
        val before = compose.onNodeWithText("row 11").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { controller.switchAnchored(10, false) { middleHeight.intValue = 240 } }
        assertEquals(before, compose.onNodeWithText("row 11").fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    @Test
    fun aLaterContentMeasurementKeepsTheOriginalAlternativeAnchor() {
        showList()
        compose.onNodeWithTag("scroll_test_list").performScrollToIndex(10)
        val before = compose.onNodeWithText("row 11").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { controller.switchAnchored(10, false) { middleHeight.intValue = 180 } }
        assertEquals(before, compose.onNodeWithText("row 11").fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.runOnIdle { middleHeight.intValue = 72 }
        assertEquals(before, compose.onNodeWithText("row 11").fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    @Test
    fun navigationCancelsAnAlternativeAnchorStillInProgress() {
        showList()
        compose.onNodeWithTag("scroll_test_list").performScrollToIndex(10)
        compose.runOnIdle {
            controller.switchAnchored(10, false) { middleHeight.intValue = 72 }
            controller.scrollToTop()
        }
        assertEquals(0 to 0, position())
        compose.mainClock.advanceTimeBy(160)
        assertEquals(0 to 0, position())
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
            val keyboardHeightPx = with(LocalDensity.current) { keyboardHeight.intValue.dp.roundToPx() }
            ImeLazyListAutoScroller(
                lazyListState = controller.listState,
                shouldFollow = controller::isAtBottom,
                onFollow = controller::snapToBottom,
                imeInsets = WindowInsets(bottom = keyboardHeightPx),
            )
            LazyColumn(
                Modifier.height((400 - keyboardHeight.intValue).dp).testTag("scroll_test_list"),
                state = controller.listState,
            ) {
                items(30, key = { it }) { index ->
                    val height = when (index) {
                        10 -> middleHeight.intValue
                        29 -> lastHeight.intValue
                        else -> 72
                    }
                    Text("row $index", Modifier.height(height.dp))
                }
                item { Spacer(Modifier.height(1.dp)) }
            }
        }
        compose.waitForIdle()
    }

    private fun assertAtBottom() {
        compose.waitForIdle()
        compose.waitUntil("The final anchor must become visible", timeoutMillis = 5_000) {
            compose.runOnUiThread { !controller.listState.canScrollForward }
        }
        compose.runOnIdle { assertFalse("The final anchor must remain visible", controller.listState.canScrollForward) }
    }

    private fun position(): Pair<Int, Int> {
        compose.waitForIdle()
        return compose.runOnIdle { controller.listState.firstVisibleItemIndex to controller.listState.firstVisibleItemScrollOffset }
    }
}
