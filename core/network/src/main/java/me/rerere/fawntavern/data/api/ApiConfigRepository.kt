package me.rerere.fawntavern.data.api

/** Mutable provider configuration contract shared by chat and API settings. */
interface ApiConfigRepository {
    fun load(): ApiConfig
    fun save(config: ApiConfig)
}
