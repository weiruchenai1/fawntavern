package me.rerere.fawntavern.ui.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.GeneratedImage
import me.rerere.fawntavern.data.chat.AttachmentStore
import me.rerere.fawntavern.data.chat.PersistedGeneratedImage
import me.rerere.fawntavern.data.settings.GlobalVariableStore

internal class AndroidChatGenerationResources(context: Context) : ChatGenerationResources {
    private val appContext = context.applicationContext

    override suspend fun persistGeneratedImage(image: GeneratedImage): PersistedGeneratedImage? =
        AttachmentStore.persistGeneratedImage(appContext, image)

    override suspend fun saveGlobalVariables(variables: Map<String, String>) {
        withContext(Dispatchers.IO) { GlobalVariableStore.set(appContext, variables) }
    }

    override fun errorText(error: Exception): String =
        appContext.getString(R.string.chat_error_fmt, error.message.orEmpty())
}
