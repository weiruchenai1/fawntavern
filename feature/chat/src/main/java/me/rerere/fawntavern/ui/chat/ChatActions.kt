package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.api.ImageGenerationSettings
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.extension.QuickReply

sealed interface ChatAction {
    data object SendMessage : ChatAction
    data class UseQuickReply(val reply: QuickReply) : ChatAction
    data class RegenerateAssistant(
        val timestamp: Long,
        val scrollToBottom: Boolean,
    ) : ChatAction
    data class RegenerateAfterUser(
        val timestamp: Long,
        val scrollToBottom: Boolean,
    ) : ChatAction
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
    data object PauseSpeaking : ChatAction
    data object ResumeSpeaking : ChatAction
    data object FastForwardSpeaking : ChatAction
    data object CycleSpeakingSpeed : ChatAction
    data object ReloadUserProfile : ChatAction
    data class UpdateUserProfile(val name: String, val description: String) : ChatAction
    data object ReloadUiSettings : ChatAction
    data object RefreshAfterDataManagement : ChatAction
    data object ReloadApiConfig : ChatAction
    data object ReloadPromptData : ChatAction
    data object RefreshCurrentCard : ChatAction
    data object RefreshExtensionSlots : ChatAction
    data object ReloadSearchConfig : ChatAction
}

sealed interface ChatEffect {
    data class ShowMessage(val text: String, val long: Boolean = false) : ChatEffect
    data object OpenModelSelector : ChatEffect
    data object ScrollToBottom : ChatEffect
    data object HideKeyboard : ChatEffect
}
