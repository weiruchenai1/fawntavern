package me.rerere.fawntavern.di

import android.content.Context
import java.io.File
import me.rerere.fawntavern.core.resource.EditorDraftStorage
import me.rerere.fawntavern.data.FileEditorDraftStorage
import me.rerere.fawntavern.data.api.ApiConfigRepository
import me.rerere.fawntavern.data.api.PreferencesApiConfigRepository
import me.rerere.fawntavern.data.chat.RoomChatDataRepository
import me.rerere.fawntavern.data.generation.NetworkGenerationGateway
import me.rerere.fawntavern.domain.chat.ChatDataRepository
import me.rerere.fawntavern.domain.GenerationGateway
import me.rerere.fawntavern.extension.AndroidExtensionGateway
import me.rerere.fawntavern.extension.AndroidPluginHostCapabilities
import me.rerere.fawntavern.extension.ExtensionGateway

/** 应用级依赖装配点，避免 ViewModel 和业务对象自行获取全局存储或网络实现。 */
internal class AppContainer(
    context: Context,
    val apiConfigRepository: ApiConfigRepository = PreferencesApiConfigRepository(context),
) {
    val editorDrafts: EditorDraftStorage = FileEditorDraftStorage(File(context.noBackupFilesDir, "editor_drafts"))
    val chatRepository: ChatDataRepository = RoomChatDataRepository(context)
    val generationGateway: GenerationGateway = NetworkGenerationGateway(apiConfigRepository)
    val extensionGateway: ExtensionGateway = AndroidExtensionGateway(context, chatRepository)
    val pluginHostCapabilities = AndroidPluginHostCapabilities(chatRepository)
    val features = AppFeatureControllers(context, chatRepository, apiConfigRepository)
}
