package me.rerere.fawntavern.ui.chat

import android.app.Application
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class MessageWebViewFocusTest {
    @Test
    fun inputFocusIsAvailableInitiallyAndAfterReuse() {
        val view = MessageWebView(
            context = RuntimeEnvironment.getApplication(),
            shell = "<html><body></body></html>",
            initialFragment = "<input><textarea></textarea><button>OK</button>",
            streaming = false,
            allowContentJavaScript = true,
            chatMessagesJson = "[]",
            frontendContextJson = "{}",
            localVariablesJson = "{}",
            globalVariablesJson = "{}",
            onPageHeight = {},
            onSetInputText = {},
            onSetChatMessage = { _, _ -> },
            onSelectChatMessageSwipe = { _, _ -> },
            onReplaceVariables = { _, _ -> },
            rpcCall = { _, _ -> "null" },
            onOpenImage = {},
            onOpenLink = {},
        )
        try {
            assertTrue(view.isFocusableInTouchMode)
            assertTrue(view.requestFocus())
            view.deactivate()
            assertFalse(view.isFocusable)
            assertFalse(view.hasFocus())
            view.bind(
                chatMessagesJson = "[]",
                frontendContextJson = "{}",
                localVariablesJson = "{}",
                globalVariablesJson = "{}",
                streaming = false,
                onPageHeight = {},
                onSetInputText = {},
                onSetChatMessage = { _, _ -> },
                onSelectChatMessageSwipe = { _, _ -> },
                onReplaceVariables = { _, _ -> },
                rpcCall = { _, _ -> "null" },
                onOpenImage = {},
                onOpenLink = {},
            )
            assertTrue(view.isFocusableInTouchMode)
            assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, view.descendantFocusability)
            assertTrue(view.requestFocus())
        } finally {
            view.destroySafely()
        }
    }
}
