package me.rerere.fawntavern.ui.components

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.CompletableDeferred
import me.rerere.fawntavern.R
import me.rerere.fawntavern.core.resource.ImportableResourceController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalTestApi::class)
class UiMotionTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun returningListKeepsItsSnapshotDuringRefreshAndAppliesAnEmptyResult() {
        val completion = CompletableDeferred<List<String>>()
        val state = ImportableListState<String>().apply {
            names = listOf("Previous preset")
            items = mapOf("Previous preset" to "Previous preset")
            hasLoaded = true
        }
        val controller = object : ImportableResourceController<String, Uri> {
            override suspend fun names() = completion.await()
            override suspend fun load(name: String) = name
            override suspend fun import(source: Uri): String = error("Unused")
            override suspend fun rename(old: String, new: String): Boolean = error("Unused")
            override suspend fun delete(name: String): Unit = error("Unused")
        }
        compose.setContent {
            MaterialTheme {
                ImportableListScreen(
                    titleRes = R.string.presets,
                    onBack = {},
                    importMimeType = "application/json",
                    emptyIcon = Lucide.FileJson,
                    emptyTitleRes = R.string.no_presets_title,
                    emptyDescRes = R.string.no_presets_desc,
                    renameLabelRes = R.string.toast_rename_preset_label,
                    deleteTitleRes = R.string.delete_preset_title,
                    deleteMsgFmtRes = R.string.delete_preset_msg_fmt,
                    controller = controller,
                    listState = state,
                    onOpen = {},
                    itemCard = { name, _, _, _ -> Text(name) },
                )
            }
        }
        compose.onNodeWithText("Previous preset").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.char_loading)).assertDoesNotExist()
        compose.runOnIdle { completion.complete(emptyList()) }
        compose.onNodeWithText(context.getString(R.string.no_presets_title)).assertExists()
        compose.onNodeWithText("Previous preset").assertDoesNotExist()
    }
}
