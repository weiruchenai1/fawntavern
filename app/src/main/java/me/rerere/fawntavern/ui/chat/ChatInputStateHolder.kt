package me.rerere.fawntavern.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.fawntavern.extension.QuickReply

/** Owns input data that must survive recomposition and Activity recreation with the ViewModel. */
internal class ChatInputStateHolder {
    val textFieldState = TextFieldState()

    var attachments by mutableStateOf<List<Attachment>>(emptyList())
        private set

    var editingTimestamp by mutableStateOf<Long?>(null)
        private set

    var quickReplies by mutableStateOf<List<QuickReply>>(emptyList())
        private set

    var text: String
        get() = textFieldState.text.toString()
        set(value) = textFieldState.setTextAndPlaceCursorAtEnd(value)

    val uiState: ChatUiState.InputState
        get() = ChatUiState.InputState(
            attachments = attachments,
            editingTimestamp = editingTimestamp,
            quickReplies = quickReplies,
        )

    fun addAttachments(values: List<Attachment>) {
        attachments = attachments + values
    }

    fun removeAttachment(value: Attachment) {
        attachments = attachments - value
    }

    fun clearDraft() {
        text = ""
        attachments = emptyList()
    }

    fun restoreDraft(draftText: String, sentAttachments: List<Attachment>) {
        if (draftText.isNotBlank()) {
            text = if (text.isBlank()) draftText else "$draftText\n$text"
        }
        val currentUris = attachments.mapTo(HashSet()) { it.uri }
        attachments = sentAttachments.filter { it.uri !in currentUris } + attachments
    }

    fun beginEditing(timestamp: Long, content: String) {
        editingTimestamp = timestamp
        text = content
    }

    fun finishEditing() {
        editingTimestamp = null
        text = ""
    }

    fun cancelEditing() = finishEditing()

    fun replaceQuickReplies(values: List<QuickReply>) {
        quickReplies = values
    }
}
