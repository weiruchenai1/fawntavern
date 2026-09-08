package me.rerere.fawntavern.ui.components

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiConfigRepository
import me.rerere.fawntavern.data.preset.PromptItem
import me.rerere.fawntavern.data.worldbook.WorldBookEntry
import me.rerere.fawntavern.di.AppContainer
import me.rerere.fawntavern.di.LocalAppContainer
import me.rerere.fawntavern.ui.character.CharacterEditorState
import me.rerere.fawntavern.ui.character.CharacterListScreen
import me.rerere.fawntavern.ui.preset.PresetListScreen
import me.rerere.fawntavern.ui.worldbook.WorldBookListScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.junit.rules.TimeoutRule
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalTestApi::class)
class EditorRestorationTest {
    @get:Rule(order = 0) val timeout = TimeoutRule.seconds(90)
    @get:Rule(order = 1) val compose = createComposeRule()
    @get:Rule(order = 2) val failureDiagnostics = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching { printFailureDiagnostics(description.methodName) }
                .onFailure(error::addSuppressed)
        }
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var editorStore: ViewModelStore

    @Test
    fun presetRouteAndUnconfirmedPromptEditsSurviveARecreatedViewModel() {
        val container = createContainer()
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
        waitForExternalWork("Confirmed prompt is saved to the preset") {
            runBlocking { controller.load("Draft preset").prompts.single().content == "unsaved prompt after recreation" }
        }
    }

    @Test
    fun characterEditorRestoresItsRouteAndChangedName() {
        val container = createContainer()
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
        waitForExternalWork("Restored character name is saved to the character file") {
            runBlocking { container.features.characterLibrary.load("Draft character").name == "Restored character name" }
        }
    }

    @Test
    fun worldBookRestoresAnUnconfirmedEntryAndPersistsItAfterConfirmation() {
        val container = createContainer()
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
        waitForExternalWork("Confirmed entry is saved to the world book") {
            runBlocking { controller.load("Draft world book").entries.getValue(1).content == "unsaved world book content" }
        }
    }

    @Test
    fun characterRestoresAnUnconfirmedGreetingAndPersistsItAfterConfirmation() {
        val container = createContainer()
        val library = container.features.characterLibrary
        runBlocking {
            library.create("Greeting character")
            val card = library.load("Greeting character")
            container.features.characterEditor.save("Greeting character", card.copy(firstMes = "Original greeting"))
            assertEquals(
                "Saving a character without talkativeness preserves its default when reloaded",
                0.5f,
                library.load("Greeting character").talkativeness,
                0f,
            )
        }
        val restoration = show(container) { CharacterListScreen(onBack = {}) }
        waitForText("Greeting character")
        compose.onNodeWithText("Greeting character").performClick()
        waitForText("Original greeting")
        compose.onNodeWithText("Original greeting").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Original greeting"))
            .performTextReplacement("Unconfirmed greeting")
        compose.onNode(hasSetTextAction() and hasText("Unconfirmed greeting")).assertExists()
        compose.runOnIdle {
            val model = editorStore["editor:character"] as? EditorDraftViewModel<*>
            val draft = model?.value as? CharacterEditorState
            assertEquals(
                "The character draft receives the unconfirmed greeting before it is checkpointed",
                "Unconfirmed greeting",
                draft?.greetingDraft,
            )
        }
        waitForCheckpoint("Unconfirmed greeting")
        assertEquals("Original greeting", runBlocking { library.load("Greeting character").firstMes })

        restoration.emulateSavedInstanceStateRestore()
        waitForText("Unconfirmed greeting")
        compose.onNode(hasSetTextAction() and hasText("Unconfirmed greeting")).assertExists()
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        waitForExternalWork("Confirmed greeting is saved to the character file") {
            runBlocking { library.load("Greeting character").firstMes == "Unconfirmed greeting" }
        }
    }

    private fun createContainer(): AppContainer {
        // 页面恢复使用真实资源与草稿文件，模型配置隔离 Android KeyStore 的平台依赖。
        val apiConfig = object : ApiConfigRepository {
            private var value = ApiConfig()
            override fun load(): ApiConfig = value
            override fun save(config: ApiConfig) { value = config }
        }
        return AppContainer(context, apiConfigRepository = apiConfig)
    }

    private fun show(container: AppContainer, screen: @Composable () -> Unit): StateRestorationTester {
        editorStore = ViewModelStore()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore get() = editorStore
        }
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                CompositionLocalProvider(LocalAppContainer provides container, LocalViewModelStoreOwner provides owner) {
                    // 同时清理 ViewModel，确保恢复读取的是文件，而不是旧进程的内存状态。
                    DisposableEffect(Unit) {
                        onDispose { editorStore.clear(); editorStore = ViewModelStore() }
                    }
                    screen()
                }
            }
        }
    }

    private fun waitForText(text: String) {
        waitForExternalWork("The editor displays: $text") {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForCheckpoint(text: String) {
        val directory = File(context.noBackupFilesDir, "editor_drafts")
        waitForExternalWork("A persisted editor checkpoint contains: $text") {
            directory.listFiles()?.any { it.extension == "json" && it.readText().contains(text) } == true
        }
    }

    // 在 Compose 清理 ViewModel 前记录现场，写入异常也会进入 Gradle 测试报告。
    private fun printFailureDiagnostics(testName: String) {
        println("Editor restoration failure: $testName")
        if (::editorStore.isInitialized) {
            editorStore.keys().sortedBy { it.toString() }.forEach { key ->
                val model = editorStore[key] as? EditorDraftViewModel<*> ?: return@forEach
                println("Editor $key: key=${model.key}, saving=${model.saving}, completedKey=${model.completedKey}")
                println("Memory draft: ${model.value}")
            }
        }
        val directory = File(context.noBackupFilesDir, "editor_drafts")
        val files = directory.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName)
        println("Checkpoint directory: $directory; files=${files.size}")
        files.forEach { file -> println("Checkpoint ${file.name}: ${file.readText()}") }
        ShadowLog.getLogs().filter { it.tag == "EditorDraft" || it.tag == "ResourceEditor" }
            .forEach { println("${it.tag}: ${it.msg}") }
        val inputs = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text
        }
        println("Text field values: $inputs")
    }

    private fun waitForExternalWork(description: String, condition: () -> Boolean) {
        compose.waitUntil(description, timeoutMillis = 15_000) {
            // IO 使用真实线程，等待期间还需执行 Android 回调并同步 Compose。
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            condition()
        }
    }
}
