package me.rerere.fawntavern.extension

import android.content.Context
import me.rerere.fawntavern.data.api.ApiConfig

internal interface ExtensionGateway {
    fun enabledExtensions(): List<Extension>
    fun config(id: String): String
    fun services(apiConfig: ApiConfig): ExtensionServices
}

internal class AndroidExtensionGateway(
    context: Context,
) : ExtensionGateway {
    private val appContext = context.applicationContext

    override fun enabledExtensions(): List<Extension> =
        ExtensionStore.enabledExtensions(appContext)

    override fun config(id: String): String = ExtensionStore.getConfig(appContext, id)

    override fun services(apiConfig: ApiConfig): ExtensionServices =
        HostServices(appContext, apiConfig)
}
