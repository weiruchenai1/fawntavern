package me.rerere.fawntavern.ui.settings

import android.content.Context
import me.rerere.fawntavern.data.search.SearchServiceOptions
import me.rerere.fawntavern.data.search.SearchCommonOptions
import me.rerere.fawntavern.data.search.SearchResult
import me.rerere.fawntavern.data.search.createSearchService
import me.rerere.fawntavern.data.settings.SearchStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidWebSearchConfigDataSource(
    private val context: Context,
) : WebSearchConfigDataSource {
    override fun services(): List<SearchServiceOptions> = SearchStore.getServices(context)
    override fun saveServices(services: List<SearchServiceOptions>) = SearchStore.setServices(context, services)
    override fun resultSize(): Int = SearchStore.getResultSize(context)
    override fun saveResultSize(size: Int) = SearchStore.setResultSize(context, size)
    override fun consumeRecoveryNotice(): Boolean = SearchStore.consumeCorruptionNotice(context)
    override suspend fun test(
        service: SearchServiceOptions,
        query: String,
        resultSize: Int,
    ): SearchResult = withContext(Dispatchers.IO) {
        createSearchService(service)
            .search(query, SearchCommonOptions(resultSize), service)
            .getOrThrow()
    }
}
