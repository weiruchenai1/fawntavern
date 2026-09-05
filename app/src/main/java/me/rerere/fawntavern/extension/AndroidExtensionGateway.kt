package me.rerere.fawntavern.extension

import android.content.Context
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.settings.DefaultModelRole
import me.rerere.fawntavern.data.settings.DefaultModelStore
import me.rerere.fawntavern.domain.chat.ChatDataRepository

internal class AndroidExtensionGateway(
    context: Context,
    private val chatRepository: ChatDataRepository,
) : ExtensionGateway {
    private val appContext = context.applicationContext

    override fun enabledExtensions(): List<Extension> = ExtensionStore.enabledExtensions(appContext)

    override fun config(id: String): String = ExtensionStore.getConfig(appContext, id)

    override fun services(apiConfig: ApiConfig): ExtensionServices = HostServices(
        config = apiConfig,
        chatRepository = chatRepository,
        modelPreference = { purpose ->
            when (purpose) {
                ExtensionModelPurpose.SUMMARY -> DefaultModelStore
                    .get(appContext, DefaultModelRole.SUMMARY.storageKey)
                    .model
                    .takeIf(String::isNotBlank)
            }
        },
    )
}
