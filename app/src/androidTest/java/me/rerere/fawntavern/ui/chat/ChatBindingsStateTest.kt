package me.rerere.fawntavern.ui.chat

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import android.net.Uri
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatBindingsStateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun restoringOverlaysKeepsSmallFlagsButNeverReplaysPendingActions() {
        val restoration = StateRestorationTester(compose)
        lateinit var overlays: ChatOverlayState
        restoration.setContent { overlays = rememberChatOverlayState() }
        compose.runOnIdle {
            overlays.showAttachment = true
            overlays.showReasoningPicker = true
            overlays.showSearch = true
            overlays.deleteSessionId = "pending-session"
            overlays.confirmRegenerate(true) { error("Must not replay a callback after recreation") }
            overlays.copyPanel = CopyPanel("preview", "large transient content")
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertTrue(overlays.showAttachment)
            assertTrue(overlays.showReasoningPicker)
            assertTrue(overlays.showSearch)
            assertEquals("pending-session", overlays.deleteSessionId)
            assertNull(overlays.pendingRegenerate)
            assertNull(overlays.copyPanel)
        }
    }

    @Test
    fun changingOnlyPersistedIdsRefreshesFrontendJsonContextAndActionRouting() {
        val message = ChatMessage(role = "assistant", ts = 100, content = "same content")
        val messages = listOf(message)
        val ids = mutableStateOf(mapOf(100L to 5))
        val initial = chatTestState()
        val state = initial.copy(
            settings = initial.settings.copy(javascriptSupport = true),
            conversation = initial.conversation.copy(current = ChatSession(id = "session")),
        )
        val actions = mutableListOf<ChatAction>()
        lateinit var frontend: ChatFrontendBindings
        compose.setContent { frontend = rememberChatFrontendBindings(state, messages, ids.value, actions::add) }
        compose.runOnIdle { assertEquals(5, JSONArray(frontend.messagesJson).getJSONObject(0).getInt("message_id")) }
        compose.runOnIdle { ids.value = mapOf(100L to 8) }
        compose.runOnIdle {
            assertEquals(8, JSONArray(frontend.messagesJson).getJSONObject(0).getInt("message_id"))
            assertEquals(8, JSONObject(frontend.contextJson(message)).getInt("messageId"))
            frontend.updateMessage(5, "stale id")
            frontend.updateMessage(8, "new id")
            assertEquals(listOf(ChatAction.UpdateMessage(message, "new id")), actions)
        }
    }

    @Test
    fun galleryResultUsesTheLatestActionHandlerAfterRecomposition() {
        val registry = CapturingRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val oldActions = mutableListOf<ChatAction>()
        val newActions = mutableListOf<ChatAction>()
        val handler = mutableStateOf<(ChatAction) -> Unit>(oldActions::add)
        lateinit var media: ChatMediaActions
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                media = rememberChatMediaActions(handler.value)
            }
        }
        compose.runOnIdle { media.pickImages() }
        compose.runOnIdle { handler.value = newActions::add }
        val uri = Uri.parse("content://test/image.png")
        compose.runOnIdle { registry.dispatchResult(registry.requestCode, listOf(uri)) }
        compose.runOnIdle {
            assertTrue(oldActions.isEmpty())
            assertEquals(listOf(ChatAction.AddAttachments(listOf(Attachment(uri, true)))), newActions)
        }
    }

    private class CapturingRegistry : ActivityResultRegistry() {
        var requestCode = -1
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            this.requestCode = requestCode
        }
    }
}
