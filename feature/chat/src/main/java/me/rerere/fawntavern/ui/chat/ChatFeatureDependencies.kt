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
    val session: ChatSessionDependencies,
    val generation: ChatGenerationDependencies,
    val platform: ChatPlatformDependencies,
)

class ChatSessionDependencies(
    val chatRepository: ChatDataRepository,
    val promptContextDataSource: ChatPromptContextDataSource,
    val userProfileDataSource: ChatUserProfileDataSource,
    val attachmentDataSource: ChatAttachmentDataSource,
    val frontendVariableDataSource: ChatFrontendVariableDataSource = EmptyChatFrontendVariableDataSource,
)

class ChatGenerationDependencies(
    val apiConfigRepository: ApiConfigRepository,
    val generationGateway: GenerationGateway,
    val extensionGateway: ExtensionGateway,
    val promptEnvironment: ChatPromptEnvironment,
    val generationResources: ChatGenerationResources,
    val searchToolDataSource: ChatSearchToolDataSource,
    val titleSettingsDataSource: ChatTitleSettingsDataSource,
)

class ChatPlatformDependencies(
    val modelDataSource: ChatModelDataSource,
    val uiSettingsDataSource: ChatUiSettingsDataSource,
    val searchSettingsDataSource: ChatWebSearchSettingsDataSource,
    val ttsControllerFactory: () -> ChatTtsController,
    val texts: ChatTextProvider,
    val initialize: () -> Unit = {},
)
