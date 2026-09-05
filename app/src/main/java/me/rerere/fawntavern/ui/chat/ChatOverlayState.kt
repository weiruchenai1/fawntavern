package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import me.rerere.fawntavern.data.chat.ChatMessage

internal data class MessageMenuTarget(
    val message: ChatMessage,
    val messageWindow: List<ChatMessage>,
    val wasLast: Boolean,
)

/** Save only small UI flags; message snapshots and pending callbacks remain transient. */
internal class ChatOverlayState {
    var showAttachment by mutableStateOf(false)
    var showReasoningPicker by mutableStateOf(false)
    var showImageGenerationSettings by mutableStateOf(false)
    var showSearch by mutableStateOf(false)
    var showCharPicker by mutableStateOf(false)
    var deleteSessionId by mutableStateOf<String?>(null)
    var menuTarget by mutableStateOf<MessageMenuTarget?>(null)
    var copyPanel by mutableStateOf<CopyPanel?>(null)
    var renameSession by mutableStateOf<Pair<String, String>?>(null)
    var pendingRegenerate by mutableStateOf<(() -> Unit)?>(null)
    var pendingDeleteCurrentVersion by mutableStateOf<(() -> Unit)?>(null)
    var pendingDeleteAllVersions by mutableStateOf<(() -> Unit)?>(null)

    fun confirmRegenerate(required: Boolean, action: () -> Unit) {
        if (required) pendingRegenerate = action else action()
    }

    fun confirmDeleteCurrentVersion(required: Boolean, action: () -> Unit) {
        if (required) pendingDeleteCurrentVersion = action else action()
    }

    fun confirmDeleteAllVersions(required: Boolean, action: () -> Unit) {
        if (required) pendingDeleteAllVersions = action else action()
    }

    companion object {
        val Saver = listSaver<ChatOverlayState, Any>(
            save = { listOf(it.showAttachment, it.showReasoningPicker, it.showImageGenerationSettings,
                it.showSearch, it.showCharPicker, it.deleteSessionId.orEmpty()) },
            restore = { saved ->
                ChatOverlayState().apply {
                    showAttachment = saved[0] as Boolean
                    showReasoningPicker = saved[1] as Boolean
                    showImageGenerationSettings = saved[2] as Boolean
                    showSearch = saved[3] as Boolean
                    showCharPicker = saved[4] as Boolean
                    deleteSessionId = (saved[5] as String).ifEmpty { null }
                }
            },
        )
    }
}

@Composable
internal fun rememberChatOverlayState(): ChatOverlayState =
    rememberSaveable(saver = ChatOverlayState.Saver) { ChatOverlayState() }
