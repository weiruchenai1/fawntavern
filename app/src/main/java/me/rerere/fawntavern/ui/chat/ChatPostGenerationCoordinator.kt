package me.rerere.fawntavern.ui.chat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.fawntavern.core.diagnostics.SafeLog
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.chat.ChatDataRepository
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.extension.ExtensionGateway
import me.rerere.fawntavern.extension.GenerationContext
import me.rerere.fawntavern.extension.GenerationLifecycle

internal class ChatPostGenerationCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val chatRepository: ChatDataRepository,
    private val extensions: ExtensionGateway,
    private val titleGenerator: ChatTitleGenerator =
        ChatTitleGenerator(context, chatRepository, extensions),
) {
    fun runExtensions(
        session: ChatSession,
        apiConfig: ApiConfig,
        userName: String,
        characterName: String,
        isCurrent: () -> Boolean,
        onSessionRefreshed: (ChatSession) -> Unit,
    ) {
        scope.launch {
            val services = extensions.services(apiConfig)
            var ran = false
            var latest = chatRepository.get(session.id) ?: session
            for (extension in extensions.enabledExtensions()) {
                if (extension !is GenerationLifecycle) continue
                ran = true
                try {
                    extension.onGenerationComplete(
                        GenerationContext(
                            session = latest,
                            charName = characterName,
                            userName = userName,
                            extState = latest.extState[extension.info.id].orEmpty(),
                            config = extensions.config(extension.info.id),
                        ),
                        services,
                    )
                } catch (error: Exception) {
                    SafeLog.warn(TAG, "extension_generation_hook_failed", error)
                }
                latest = chatRepository.get(session.id) ?: latest
            }
            if (ran && isCurrent()) {
                chatRepository.get(session.id)?.let(onSessionRefreshed)
            }
        }
    }

    fun generateTitle(
        session: ChatSession,
        force: Boolean,
        chatModel: String,
        apiConfig: ApiConfig,
        userName: String,
        characterName: String,
        onTitle: (String) -> Unit,
    ) {
        scope.launch {
            try {
                titleGenerator.generate(
                    session = session,
                    force = force,
                    chatModel = chatModel,
                    apiConfig = apiConfig,
                    userName = userName,
                    charName = characterName,
                )?.let(onTitle)
            } catch (error: Exception) {
                SafeLog.warn(TAG, "session_title_generation_failed", error)
            }
        }
    }

    private companion object {
        const val TAG = "ChatPostGeneration"
    }
}
