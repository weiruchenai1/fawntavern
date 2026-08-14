package me.rerere.fawntavern.ui.settings

import android.content.Context
import me.rerere.fawntavern.data.search.SearchServiceOptions
import me.rerere.fawntavern.data.settings.SearchStore

internal data class WebSearchConfigState(
    val services: List<SearchServiceOptions>,
    val resultSize: Int,
    val recovered: Boolean,
)

internal interface WebSearchConfigDataSource {
    fun services(): List<SearchServiceOptions>
    fun saveServices(services: List<SearchServiceOptions>)
    fun resultSize(): Int
    fun saveResultSize(size: Int)
    fun consumeRecoveryNotice(): Boolean
}

internal class AndroidWebSearchConfigDataSource(
    private val context: Context,
) : WebSearchConfigDataSource {
    override fun services(): List<SearchServiceOptions> = SearchStore.getServices(context)
    override fun saveServices(services: List<SearchServiceOptions>) = SearchStore.setServices(context, services)
    override fun resultSize(): Int = SearchStore.getResultSize(context)
    override fun saveResultSize(size: Int) = SearchStore.setResultSize(context, size)
    override fun consumeRecoveryNotice(): Boolean = SearchStore.consumeCorruptionNotice(context)
}

internal class WebSearchConfigController(
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
}
