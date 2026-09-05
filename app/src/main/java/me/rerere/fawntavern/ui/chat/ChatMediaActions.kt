package me.rerere.fawntavern.ui.chat

import android.content.ClipData
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R

/** Platform launchers live above destination switching so pending results still reach the chat. */
internal class ChatMediaActions(
    val takePhoto: () -> Unit,
    val pickImages: () -> Unit,
    val pickFiles: () -> Unit,
    val copyText: (String) -> Unit,
    val saveText: (String) -> Unit,
)

@Composable
internal fun rememberChatMediaActions(onAction: (ChatAction) -> Unit): ChatMediaActions {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val mediaInput = remember(ctx) { ChatMediaInput(ctx) }
    var cameraImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var txtSaveContent by remember { mutableStateOf<String?>(null) }

    fun copyText(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", text)))
        }
        Toast.makeText(ctx, resources.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    val saveTxtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val text = txtSaveContent
        txtSaveContent = null
        if (uri != null && text != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(text.toByteArray(Charsets.UTF_8))
                        } ?: error("Unable to open TXT output stream")
                    }.isSuccess
                }
                Toast.makeText(
                    ctx,
                    resources.getString(if (saved) R.string.file_saved else R.string.file_save_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        onAction(ChatAction.AddAttachments(uris.map { Attachment(it, isImage = true) }))
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        onAction(ChatAction.AddAttachments(uris.map { Attachment(it, isImage = mediaInput.isImage(it)) }))
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraImageUri?.let(Uri::parse)?.let { uri ->
            if (ok) onAction(ChatAction.AddAttachments(listOf(Attachment(uri, isImage = true))))
            else mediaInput.discardCameraFile(uri)
        }
        cameraImageUri = null
    }

    return ChatMediaActions(
        takePhoto = {
            val uri = mediaInput.createCameraUri()
            cameraImageUri = uri.toString()
            cameraLauncher.launch(uri)
        },
        pickImages = { galleryLauncher.launch("image/*") },
        pickFiles = { fileLauncher.launch("*/*") },
        copyText = ::copyText,
        saveText = { text ->
            txtSaveContent = text
            saveTxtLauncher.launch("FawnTavern-content.txt")
        },
    )
}
