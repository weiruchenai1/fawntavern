package me.rerere.fawntavern.ui.chat

import android.graphics.Bitmap
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.search.SearchServiceOptions
import me.rerere.fawntavern.data.speech.TtsUiState
import me.rerere.fawntavern.extension.QuickReply

internal data class ChatUiState(
    val conversation: ConversationState,
    val input: InputState,
    val generation: GenerationState,
    val profile: ProfileState,
    val model: ModelState,
    val search: SearchState,
    val settings: ChatUiSettings,
    val promptContextFailures: List<me.rerere.fawntavern.data.PromptContextLoader.LoadFailure>,
    val sendError: String?,
) {
    data class ConversationState(
        val sessions: List<ChatSession>,
        val current: ChatSession?,
        val card: CharacterCard?,
        val characterImage: Bitmap?,
        val overlays: Map<Long, ChatMessage>,
        val displayRegexScripts: List<CharRegex>,
    )

    data class InputState(
        val attachments: List<Attachment>,
        val editingTimestamp: Long?,
        val quickReplies: List<QuickReply>,
    )

    data class GenerationState(
        val running: Boolean,
        val targetTimestamp: Long?,
    )

    data class ProfileState(
        val userName: String,
        val userAvatar: Bitmap?,
        val speakingTimestamp: Long?,
        val tts: TtsUiState,
    )

    data class ModelState(
        val apiConfig: ApiConfig,
        val revision: Int,
        val displaySpec: String?,
        val reasoning: ReasoningLevel,
        val imageGeneration: ImageGenerationSettings,
        val imageGenerationAvailable: Boolean,
    )

    data class SearchState(
        val enabled: Boolean,
        val providerIndex: Int,
        val providerName: String,
        val services: List<SearchServiceOptions>,
        val builtInAvailable: Boolean,
        val builtInEnabled: Boolean,
    )
}

internal sealed interface ChatAction {
    data object NewChat : ChatAction
    data class OpenSession(val id: String) : ChatAction
    data class DeleteSession(val id: String) : ChatAction
    data class RenameSession(val id: String, val title: String) : ChatAction
    data class SetSessionPinned(val id: String, val pinned: Boolean) : ChatAction
    data class RegenerateTitle(val id: String) : ChatAction
    data class OpenCharacter(val fileName: String, val displayName: String) : ChatAction
    data class SelectModel(val providerId: String, val modelId: String) : ChatAction
    data class UpdateReasoning(val level: ReasoningLevel) : ChatAction
    data class UpdateImageGeneration(val settings: ImageGenerationSettings) : ChatAction
    data object StopGeneration : ChatAction
    data object ToggleSearch : ChatAction
    data object ToggleBuiltInSearch : ChatAction
    data class SelectSearchProvider(val index: Int) : ChatAction
    data class AddAttachments(val values: List<Attachment>) : ChatAction
    data class RemoveAttachment(val value: Attachment) : ChatAction
    data class SetInputText(val text: String) : ChatAction
    data object CancelEdit : ChatAction
    data class StartEdit(val timestamp: Long) : ChatAction
    data class SwitchAlternative(val timestamp: Long, val direction: Int) : ChatAction
    data class DeleteMessage(val timestamp: Long) : ChatAction
    data class DeleteAllVersions(val timestamp: Long) : ChatAction
    data class UpdateMessage(val timestamp: Long, val content: String) : ChatAction
    data class ClearOverlay(val timestamp: Long) : ChatAction
    data class SpeakMessage(val timestamp: Long) : ChatAction
    data object StopSpeaking : ChatAction
    data object ReloadUserProfile : ChatAction
    data class UpdateUserProfile(val name: String, val description: String) : ChatAction
    data object ConsumeSendError : ChatAction
    data object ConsumePromptContextFailures : ChatAction
}
