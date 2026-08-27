package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.api.ApiConfigRepository
import me.rerere.fawntavern.domain.GenerationGateway
import me.rerere.fawntavern.domain.chat.ChatDataRepository
import me.rerere.fawntavern.extension.ExtensionGateway

interface ChatTextProvider {
    val defaultPresetName: String
    val defaultCharacterName: String
    val selectModelFirst: String
    val fileTooLarge: String
    val attachmentFailed: String

    fun promptContextLoadFailed(names: String): String
    fun generationFailed(message: String): String
    fun rollbackFailed(message: String): String
    fun sendFailed(message: String): String
}

class ChatFeatureDependencies(
    val chatRepository: ChatDataRepository,
    val apiConfigRepository: ApiConfigRepository,
    val generationGateway: GenerationGateway,
    val extensionGateway: ExtensionGateway,
    val modelDataSource: ChatModelDataSource,
    val uiSettingsDataSource: ChatUiSettingsDataSource,
    val promptContextDataSource: ChatPromptContextDataSource,
    val userProfileDataSource: ChatUserProfileDataSource,
    val searchSettingsDataSource: ChatWebSearchSettingsDataSource,
    val attachmentDataSource: ChatAttachmentDataSource,
    val promptEnvironment: ChatPromptEnvironment,
    val generationResources: ChatGenerationResources,
    val searchToolDataSource: ChatSearchToolDataSource,
    val titleSettingsDataSource: ChatTitleSettingsDataSource,
    val ttsControllerFactory: () -> ChatTtsController,
    val texts: ChatTextProvider,
    val initialize: () -> Unit = {},
)
