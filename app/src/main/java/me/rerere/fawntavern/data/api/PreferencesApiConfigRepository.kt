package me.rerere.fawntavern.data.api

import android.content.Context

internal class PreferencesApiConfigRepository(
    context: Context,
) : ApiConfigRepository {
    private val appContext = context.applicationContext

    override fun load(): ApiConfig = ApiConfigStore.loadConfig(appContext)
    override fun save(config: ApiConfig) = ApiConfigStore.saveConfig(appContext, config)
}
