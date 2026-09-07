package me.rerere.fawntavern.ui.components

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.preset.PromptItem
import me.rerere.fawntavern.data.worldbook.WorldBookEntry
import me.rerere.fawntavern.di.AppContainer
import me.rerere.fawntavern.di.LocalAppContainer
import me.rerere.fawntavern.ui.character.CharacterListScreen
import me.rerere.fawntavern.ui.preset.PresetListScreen
import me.rerere.fawntavern.ui.worldbook.WorldBookListScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EditorRestorationTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun presetRouteAndUnconfirmedPromptEditsSurviveARecreatedViewModel() {
        val container = AppContainer(context)
        val controller = container.features.presets
        runBlocking {
            val preset = controller.create("Draft preset")
            controller.save(preset.copy(prompts = listOf(PromptItem("prompt", name = "Draft prompt", content = "before"))))
        }
        val restoration = show(container) { PresetListScreen(onBack = {}) }
        waitForText("Draft preset")
        compose.onNodeWithText("Draft preset").performClick()
        waitForText(context.getString(R.string.prompts) + " 1")
        compose.onNodeWithText(context.getString(R.string.prompts) + " 1").performClick()
        compose.onNodeWithText("Draft prompt").performClick()
        compose.onNode(hasSetTextAction() and hasText("before")).performTextReplacement("unsaved prompt after recreation")
        waitForCheckpoint("unsaved prompt after recreation")

        restoration.emulateSavedInstanceStateRestore()
        waitForText("unsaved prompt after recreation")
        compose.onNode(hasSetTextAction() and hasText("unsaved prompt after recreation")).assertExists()
        compose.onNodeWithText(context.getString(R.string.save)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.waitUntil(15_000) {
            runBlocking { controller.load("Draft preset").prompts.single().content == "unsaved prompt after recreation" }
        }
    }

    @Test
    fun characterEditorRestoresItsRouteAndChangedName() {
        val container = AppContainer(context)
        runBlocking { container.features.characterLibrary.create("Draft character") }
        val restoration = show(container) { CharacterListScreen(onBack = {}) }
        waitForText("Draft character")
        compose.onNodeWithText("Draft character").performClick()
        compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.char_name_label)))
            .performTextReplacement("Restored character name")
        waitForCheckpoint("Restored character name")

        restoration.emulateSavedInstanceStateRestore()
        waitForText("Restored character name")
        compose.onNode(hasSetTextAction() and hasText("Restored character name")).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.waitUntil(15_000) {
            runBlocking { container.features.characterLibrary.load("Draft character").name == "Restored character name" }
        }
    }

    @Test
    fun worldBookRestoresAnUnconfirmedEntryAndPersistsItAfterConfirmation() {
        val container = AppContainer(context)
        val controller = container.features.worldBooks
        val entry = WorldBookEntry(1, emptyList(), "Draft entry", "before", constant = true)
        runBlocking {
            controller.create("Draft world book")
            controller.saveEntries("Draft world book", listOf(entry))
        }
        val restoration = show(container) { WorldBookListScreen(onBack = {}) }
        waitForText("Draft world book")
        compose.onNodeWithText("Draft world book").performClick()
        waitForText("Draft entry")
        compose.onNodeWithContentDescription(context.getString(R.string.edit)).performClick()
        compose.onNode(hasSetTextAction() and hasText("before"))
            .performScrollTo().performTextReplacement("unsaved world book content")
        waitForCheckpoint("unsaved world book content")
        assertEquals("before", runBlocking { controller.load("Draft world book").entries.getValue(1).content })

        restoration.emulateSavedInstanceStateRestore()
        waitForText("unsaved world book content")
        compose.onNode(hasSetTextAction() and hasText("unsaved world book content")).assertExists()
        compose.onNodeWithText(context.getString(R.string.save)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.waitUntil(15_000) {
            runBlocking { controller.load("Draft world book").entries.getValue(1).content == "unsaved world book content" }
        }
    }

    @Test
    fun characterRestoresAnUnconfirmedGreetingAndPersistsItAfterConfirmation() {
        val container = AppContainer(context)
        val library = container.features.characterLibrary
        runBlocking {
            library.create("Greeting character")
            val card = library.load("Greeting character")
            container.features.characterEditor.save("Greeting character", card.copy(firstMes = "Original greeting"))
        }
        val restoration = show(container) { CharacterListScreen(onBack = {}) }
        waitForText("Greeting character")
        compose.onNodeWithText("Greeting character").performClick()
        waitForText("Original greeting")
        compose.onNodeWithText("Original greeting").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Original greeting"))
            .performTextReplacement("Unconfirmed greeting")
        waitForCheckpoint("Unconfirmed greeting")
        assertEquals("Original greeting", runBlocking { library.load("Greeting character").firstMes })

        restoration.emulateSavedInstanceStateRestore()
        waitForText("Unconfirmed greeting")
        compose.onNode(hasSetTextAction() and hasText("Unconfirmed greeting")).assertExists()
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.waitUntil(15_000) {
            runBlocking { library.load("Greeting character").firstMes == "Unconfirmed greeting" }
        }
    }

    private fun show(container: AppContainer, screen: @Composable () -> Unit): StateRestorationTester {
        var store = ViewModelStore()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore get() = store
        }
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                CompositionLocalProvider(LocalAppContainer provides container, LocalViewModelStoreOwner provides owner) {
                    // 同时清理 ViewModel，确保恢复读取的是文件，而不是旧进程的内存状态。
                    DisposableEffect(Unit) {
                        onDispose { store.clear(); store = ViewModelStore() }
                    }
                    screen()
                }
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForCheckpoint(text: String) {
        val directory = File(context.noBackupFilesDir, "editor_drafts")
        compose.waitUntil(15_000) {
            directory.listFiles()?.any { it.extension == "json" && it.readText().contains(text) } == true
        }
    }
}
