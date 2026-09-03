package me.rerere.fawntavern.ui.api

import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.withValidCurrentModel

data class ApiConfigLoadResult(
    val config: ApiConfig,
    val recovered: Boolean,
)

interface ApiConfigDataSource {
    fun load(): ApiConfig
    fun save(config: ApiConfig)
    fun consumeRecoveryNotice(): Boolean
}

class ApiConfigController(
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
