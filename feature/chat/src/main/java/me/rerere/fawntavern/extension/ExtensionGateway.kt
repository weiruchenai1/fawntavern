package me.rerere.fawntavern.extension

import me.rerere.fawntavern.data.api.ApiConfig

interface ExtensionGateway {
    fun enabledExtensions(): List<Extension>
    fun config(id: String): String
    fun services(apiConfig: ApiConfig): ExtensionServices
}
