package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.data.settings.DefaultModelStore
import me.rerere.fawntavern.data.settings.DefaultModelPrompts
import me.rerere.fawntavern.data.settings.DefaultModelRole

internal class AndroidChatTitleSettingsDataSource(
    private val context: Context,
) : ChatTitleSettingsDataSource {
    override fun resolveModel(chatModel: String): Pair<String, String>? =
        DefaultModelStore.resolveModel(context, DefaultModelRole.TITLE.storageKey, chatModel)

    override fun promptTemplate(): String {
        val entry = DefaultModelStore.get(context, DefaultModelRole.TITLE.storageKey)
        return entry.prompt.ifBlank { DefaultModelPrompts.TITLE }
    }
}
