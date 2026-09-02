package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.settings.PromptLogStore
import me.rerere.fawntavern.di.AppContainer
import me.rerere.fawntavern.domain.PromptLog
import me.rerere.fawntavern.extension.BuiltinExtensions

internal fun createChatFeatureDependencies(
    context: Context,
    container: AppContainer,
): ChatFeatureDependencies {
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
        attachmentDataSource = AndroidChatAttachmentDataSource(appContext, container.chatRepository),
        promptEnvironment = AndroidChatPromptEnvironment(appContext, container.extensionGateway),
        generationResources = AndroidChatGenerationResources(appContext),
        searchToolDataSource = AndroidChatSearchToolDataSource(appContext),
        titleSettingsDataSource = AndroidChatTitleSettingsDataSource(appContext),
        ttsControllerFactory = { AndroidChatTtsController(appContext) },
        texts = AndroidChatTextProvider(appContext),
        frontendVariableDataSource = AndroidChatFrontendVariableDataSource(appContext),
        initialize = {
            BuiltinExtensions.registerAll()
            PromptLog.enabled = PromptLogStore.isEnabled(appContext)
        },
    )
}

private class AndroidChatTextProvider(
    private val context: Context,
) : ChatTextProvider {
    override val defaultPresetName: String
        get() = context.getString(R.string.default_preset)
    override val defaultCharacterName: String
        get() = context.getString(R.string.default_character)
    override val selectModelFirst: String
        get() = context.getString(R.string.select_model_first)
    override val fileTooLarge: String
        get() = context.getString(R.string.file_too_large_to_send)
    override val attachmentFailed: String
        get() = context.getString(R.string.attachment_send_failed)

    override fun promptContextLoadFailed(names: String): String =
        context.getString(R.string.prompt_context_load_failed_fmt, names)

    override fun generationFailed(message: String): String =
        context.getString(R.string.chat_generation_failed_fmt, message)

    override fun rollbackFailed(message: String): String =
        context.getString(R.string.chat_send_rollback_failed_fmt, message)

    override fun sendFailed(message: String): String =
        context.getString(R.string.chat_send_failed_fmt, message)
}
