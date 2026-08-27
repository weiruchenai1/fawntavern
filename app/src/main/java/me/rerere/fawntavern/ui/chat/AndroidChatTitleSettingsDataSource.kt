package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.data.settings.DefaultModelStore

internal class AndroidChatTitleSettingsDataSource(
    private val context: Context,
) : ChatTitleSettingsDataSource {
    override fun resolveModel(chatModel: String): Pair<String, String>? =
        DefaultModelStore.resolveModel(context, DefaultModelStore.ROLE_TITLE, chatModel)

    override fun promptTemplate(): String {
        val entry = DefaultModelStore.get(context, DefaultModelStore.ROLE_TITLE)
        return entry.prompt.ifBlank { DefaultModelStore.DEFAULT_TITLE_PROMPT }
    }
}
