package me.rerere.fawntavern.ui.api

import android.content.Context
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiConfigStore

internal class AndroidApiConfigDataSource(
    private val context: Context,
) : ApiConfigDataSource {
    override fun load(): ApiConfig = ApiConfigStore.loadConfig(context)
    override fun save(config: ApiConfig) = ApiConfigStore.saveConfig(context, config)
    override fun consumeRecoveryNotice(): Boolean = ApiConfigStore.consumeCorruptionNotice(context)
}
