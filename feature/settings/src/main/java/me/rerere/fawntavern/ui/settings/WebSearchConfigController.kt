package me.rerere.fawntavern.ui.settings

import me.rerere.fawntavern.data.search.SearchServiceOptions
import me.rerere.fawntavern.data.search.SearchResult

data class WebSearchConfigState(
    val services: List<SearchServiceOptions>,
    val resultSize: Int,
    val recovered: Boolean,
)

interface WebSearchConfigDataSource {
    fun services(): List<SearchServiceOptions>
    fun saveServices(services: List<SearchServiceOptions>)
    fun resultSize(): Int
    fun saveResultSize(size: Int)
    fun consumeRecoveryNotice(): Boolean
    suspend fun test(service: SearchServiceOptions, query: String, resultSize: Int): SearchResult =
        error("Search testing is not available")
}

class WebSearchConfigController(
    private val dataSource: WebSearchConfigDataSource,
) {
    fun load(): WebSearchConfigState = WebSearchConfigState(
        services = dataSource.services(),
        resultSize = dataSource.resultSize().coerceIn(3, 10),
        recovered = dataSource.consumeRecoveryNotice(),
    )

    fun replace(state: WebSearchConfigState, services: List<SearchServiceOptions>): WebSearchConfigState {
        require(services.isNotEmpty())
        dataSource.saveServices(services)
        return state.copy(services = services)
    }

    fun add(state: WebSearchConfigState, service: SearchServiceOptions): WebSearchConfigState =
        replace(state, state.services + service)

    fun remove(state: WebSearchConfigState, id: String): WebSearchConfigState {
        if (state.services.size <= 1) return state
        return replace(state, state.services.filterNot { it.id == id })
    }

    fun setResultSize(state: WebSearchConfigState, size: Int): WebSearchConfigState {
        val normalized = size.coerceIn(3, 10)
        dataSource.saveResultSize(normalized)
        return state.copy(resultSize = normalized)
    }

    suspend fun test(service: SearchServiceOptions, query: String, resultSize: Int): SearchResult =
        dataSource.test(service, query.trim(), resultSize.coerceIn(3, 10))
}
