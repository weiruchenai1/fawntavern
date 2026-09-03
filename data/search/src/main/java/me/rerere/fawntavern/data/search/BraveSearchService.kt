package me.rerere.fawntavern.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.fawntavern.data.api.Http
import me.rerere.fawntavern.data.search.SearchResult.SearchResultItem
import okhttp3.Request
import java.net.URLEncoder

/** Brave Search API（api.search.brave.com），需订阅 token */
object BraveSearchService : SearchService<SearchServiceOptions.BraveOptions> {
    override val name: String = "Brave"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BraveOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.search.brave.com/res/v1/web/search" +
                "?q=${URLEncoder.encode(query, "UTF-8")}&count=${commonOptions.resultSize}"
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("X-Subscription-Token", serviceOptions.apiKey)
                .build()
            val response = Http.client.newCall(request).await()
            if (!response.isSuccessful) error("Brave search failed with code ${response.code}: ${response.message}")
            val resp = searchJson.decodeFromString<BraveSearchResponse>(response.body.string())
            SearchResult(
                items = resp.web?.results?.map {
                    SearchResultItem(it.title, it.url, it.description ?: "")
                } ?: emptyList(),
            )
        }
    }

    @Serializable
    data class BraveSearchResponse(
        val web: WebResults? = null,
    )

    @Serializable
    data class WebResults(val results: List<WebResult>? = null)

    @Serializable
    data class WebResult(
        val title: String,
        val url: String,
        val description: String? = null,
    )
}
