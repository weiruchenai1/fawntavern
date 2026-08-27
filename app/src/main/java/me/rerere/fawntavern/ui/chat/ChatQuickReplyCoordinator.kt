package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.extension.ExtensionGateway
import me.rerere.fawntavern.extension.QuickReplyProvider

/** Projects enabled extension quick replies into chat input state. */
internal class ChatQuickReplyCoordinator(
    private val extensions: ExtensionGateway,
    private val input: ChatInputStateHolder,
) {
    fun refresh() {
        val replies = extensions.enabledExtensions().flatMap { extension ->
            if (extension is QuickReplyProvider) {
                extension.quickReplies(extensions.config(extension.info.id))
            } else {
                emptyList()
            }
        }
        input.replaceQuickReplies(replies)
    }
}
