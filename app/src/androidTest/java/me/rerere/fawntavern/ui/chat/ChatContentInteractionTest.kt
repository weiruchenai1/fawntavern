package me.rerere.fawntavern.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.paging.PagingData
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.chat.MessageAlternatives
import me.rerere.fawntavern.data.chat.MsgAlt
import me.rerere.fawntavern.data.settings.Preferences
import me.rerere.fawntavern.data.speech.TtsUiState
import me.rerere.fawntavern.di.AppContainer
import me.rerere.fawntavern.di.LocalAppContainer
import me.rerere.fawntavern.ui.theme.FawnTavernTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real chat layout with controlled state: no model calls or writes to chat history. */
class ChatContentInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val input = TextFieldState()
    private val actions = mutableListOf<ChatAction>()
    private val effects = MutableSharedFlow<ChatEffect>(extraBufferCapacity = 8)
    private val frontendEvents = MutableSharedFlow<ChatFrontendEvent>()
    private val pages = MutableStateFlow<PagingData<ChatMessage>>(PagingData.empty())
    private val state = mutableStateOf(chatTestState())

    @Test
    fun sendAndStopUseCurrentGenerationStateAndKeepTheDraft() {
        showChat()
        compose.onNode(hasSetTextAction()).performTextInput("draft for model")
        button(R.string.send).performClick()
        compose.runOnIdle {
            assertTrue(actions.contains(ChatAction.SendMessage))
            state.value = state.value.copy(generation = ChatGenerationState(true, null))
        }
        button(R.string.stop).performClick()
        compose.runOnIdle {
            assertEquals(ChatAction.StopGeneration, actions.last())
            assertEquals("draft for model", input.text.toString())
            state.value = state.value.copy(generation = ChatGenerationState(false, null))
        }
        button(R.string.send).assertExists()
        button(R.string.stop).assertDoesNotExist()
    }

    @Test
    fun switchingSessionPinsItsFirstPageAndDropsOldMessages() {
        showChat(history("first"))
        list().performScrollToIndex(8)
        compose.runOnIdle { selectSession("second", history("second")) }
        compose.onNodeWithText("second 39").assertIsDisplayed()
        compose.onNodeWithText("first 8").assertDoesNotExist()
        compose.runOnIdle { selectSession("empty", emptyList()) }
        list().assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.chat_empty_hint)).assertIsDisplayed()
    }

    @Test
    fun returningFromSettingsPreservesHistoryPositionAndDraft() {
        showChat(history("history"))
        compose.onNode(hasSetTextAction()).performTextInput("unfinished draft")
        Espresso.closeSoftKeyboard()
        list().performScrollToIndex(12)
        val before = compose.onNodeWithText("history 12").fetchSemanticsNode().boundsInRoot.top
        button(R.string.menu).performClick()
        button(R.string.settings).performClick()
        button(R.string.back).performClick()
        // Pages opened from the drawer intentionally return to that drawer.
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithText("history 12").fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.runOnIdle { assertEquals("unfinished draft", input.text.toString()) }
    }

    @Test
    fun switchingAlternativeUpdatesOnlyThatMessageAndKeepsFollowingMessages() {
        val message = ChatMessage(
            role = "assistant", ts = 10, content = "original reply",
            alts = listOf(MsgAlt(content = "original reply"), MsgAlt(content = "alternative reply")),
        )
        showChat(listOf(message, ChatMessage(role = "user", ts = 20, content = "following message")))
        val followingBefore = compose.onNodeWithText("following message").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("next_alternative_10").performClick()
        compose.runOnIdle {
            val action = actions.filterIsInstance<ChatAction.SwitchAlternative>().single()
            assertEquals(1, action.direction)
            val switched = MessageAlternatives.switch(message, action.direction)!!
            state.value = state.value.copy(conversation = state.value.conversation.copy(overlays = mapOf(10L to switched)))
        }
        compose.onNodeWithText("alternative reply").assertIsDisplayed()
        compose.onNodeWithText("following message").assertIsDisplayed()
        compose.onNodeWithText("2/2").assertIsDisplayed()
        assertEquals(followingBefore, compose.onNodeWithText("following message").fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.onNodeWithTag("next_alternative_10").assertIsNotEnabled()
    }

    @Test
    fun sendEffectReturnsToBottomAfterReadingHistory() {
        showChat(history("message"))
        list().performScrollToIndex(5)
        compose.onNodeWithText("message 5").assertIsDisplayed()
        compose.runOnIdle { assertTrue(effects.tryEmit(ChatEffect.ScrollToBottom)) }
        compose.onNodeWithText("message 39").assertIsDisplayed()
    }

    @Test
    fun regenerateConfirmationCanBeCancelledAndOnlyDispatchesOnConfirm() {
        state.value = state.value.copy(settings = state.value.settings.copy(confirmRegenerate = true))
        showChat(listOf(ChatMessage(role = "assistant", ts = 10, content = "reply")))
        button(R.string.regenerate).performClick()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertTrue(actions.none { it is ChatAction.RegenerateAssistant }) }
        button(R.string.regenerate).performClick()
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(ChatAction.RegenerateAssistant(10, true)), actions.filterIsInstance<ChatAction.RegenerateAssistant>())
        }
    }

    private fun showChat(messages: List<ChatMessage> = emptyList()) {
        selectSession("first", messages)
        val container = AppContainer(context)
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                FawnTavernTheme {
                    ChatContent(state.value, input, pages, effects, frontendEvents, actions::add)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun selectSession(id: String, messages: List<ChatMessage>) {
        val session = ChatSession(id = id, messages = messages)
        state.value = state.value.copy(conversation = state.value.conversation.copy(
            current = session, sessions = listOf(session), overlays = emptyMap(),
        ))
        pages.value = PagingData.from(messages)
    }

    private fun history(prefix: String) = (0..39).map {
        ChatMessage(role = "user", ts = it.toLong() + 1, content = "$prefix $it")
    }

    private fun button(resource: Int) = compose.onNodeWithContentDescription(context.getString(resource))
    private fun list() = compose.onNodeWithTag("chat_messages")

}

internal fun chatTestState(): ChatUiState {
    val settings = ChatUiSettingsController(object : ChatUiSettingsDataSource {
        override fun preferences() = Preferences(
            userMarkdown = false, characterMarkdown = false, htmlCssRendering = false,
            showModelIcon = false, sidebarHaptic = false, longPressHaptic = false,
        )
        override fun fontScale() = 1f
    }).load()
    return ChatUiState(
        ChatConversationState(emptyList(), null, null, null, emptyMap(), emptyList()),
        ChatInputState(emptyList(), null, emptyList()),
        ChatGenerationState(false, null),
        ChatProfileState("Tester", null, null, TtsUiState()),
        ChatUiState.ModelState(ApiConfig(), 0, null, ReasoningLevel.AUTO, ImageGenerationSettings(), false),
        ChatSearchState(false, 0, "", emptyList(), false, false),
        settings,
    )
}
