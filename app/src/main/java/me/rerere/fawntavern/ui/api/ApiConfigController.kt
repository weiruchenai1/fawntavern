package me.rerere.fawntavern.ui.api

import android.content.Context
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiConfigStore
import me.rerere.fawntavern.data.api.withValidCurrentModel

internal data class ApiConfigLoadResult(
    val config: ApiConfig,
    val recovered: Boolean,
)

internal interface ApiConfigDataSource {
    fun load(): ApiConfig
    fun save(config: ApiConfig)
    fun consumeRecoveryNotice(): Boolean
}

internal class AndroidApiConfigDataSource(
    private val context: Context,
) : ApiConfigDataSource {
    override fun load(): ApiConfig = ApiConfigStore.loadConfig(context)
    override fun save(config: ApiConfig) = ApiConfigStore.saveConfig(context, config)
    override fun consumeRecoveryNotice(): Boolean = ApiConfigStore.consumeCorruptionNotice(context)
}

internal class ApiConfigController(
    private val dataSource: ApiConfigDataSource,
) {
    fun load(): ApiConfigLoadResult = ApiConfigLoadResult(
        config = dataSource.load().withValidCurrentModel(),
        recovered = dataSource.consumeRecoveryNotice(),
    )

    fun save(config: ApiConfig): ApiConfig {
        val normalized = config.withValidCurrentModel()
        dataSource.save(normalized)
        return normalized
    }
}
