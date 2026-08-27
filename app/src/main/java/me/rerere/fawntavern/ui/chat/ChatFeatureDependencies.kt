package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.data.api.ApiConfigRepository
import me.rerere.fawntavern.domain.chat.ChatDataRepository
import me.rerere.fawntavern.data.settings.PromptLogStore
import me.rerere.fawntavern.di.AppContainer
import me.rerere.fawntavern.domain.GenerationGateway
import me.rerere.fawntavern.extension.ExtensionGateway

/** Feature-scoped dependency graph assembled outside [ChatViewModel]. */
internal class ChatFeatureDependencies(
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
    val promptLogEnabled: Boolean,
) {
    companion object {
        fun create(context: Context, container: AppContainer): ChatFeatureDependencies {
            val appContext = context.applicationContext
            return ChatFeatureDependencies(
                chatRepository = container.chatRepository,
                apiConfigRepository = container.apiConfigRepository,
                generationGateway = container.generationGateway,
                extensionGateway = container.extensionGateway,
                modelDataSource = AndroidChatModelDataSource(appContext),
                uiSettingsDataSource = AndroidChatUiSettingsDataSource(appContext),
                promptContextDataSource = AndroidChatPromptContextDataSource(appContext),
                userProfileDataSource = AndroidChatUserProfileDataSource(appContext),
                searchSettingsDataSource = AndroidChatWebSearchSettingsDataSource(appContext),
                attachmentDataSource = AndroidChatAttachmentDataSource(
                    appContext,
                    container.chatRepository,
                ),
                promptEnvironment = AndroidChatPromptEnvironment(
                    appContext,
                    container.extensionGateway,
                ),
                generationResources = AndroidChatGenerationResources(appContext),
                searchToolDataSource = AndroidChatSearchToolDataSource(appContext),
                promptLogEnabled = PromptLogStore.isEnabled(appContext),
            )
        }
    }
}
