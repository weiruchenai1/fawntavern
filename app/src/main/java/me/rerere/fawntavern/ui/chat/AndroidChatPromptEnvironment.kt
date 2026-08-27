package me.rerere.fawntavern.ui.chat

import android.content.Context
import java.io.File
import me.rerere.fawntavern.data.settings.GlobalVariableStore
import me.rerere.fawntavern.data.settings.UserProfileStore
import me.rerere.fawntavern.data.settings.WorldInfoSettingsStore
import me.rerere.fawntavern.data.worldbook.WorldInfoSettings
import me.rerere.fawntavern.extension.Extension
import me.rerere.fawntavern.extension.ExtensionGateway

internal class AndroidChatPromptEnvironment(
    private val context: Context,
    private val extensions: ExtensionGateway,
) : ChatPromptEnvironment {
    override fun enabledExtensions(): List<Extension> = extensions.enabledExtensions()

    override fun extensionConfig(id: String): String = extensions.config(id)

    override fun globalVariables(): Map<String, String> = GlobalVariableStore.get(context)

    override fun userDescription(): String = UserProfileStore.getDescription(context)

    override fun worldInfoSettings(): WorldInfoSettings = WorldInfoSettingsStore.get(context)

    override fun filesDir(): File = context.filesDir
}
