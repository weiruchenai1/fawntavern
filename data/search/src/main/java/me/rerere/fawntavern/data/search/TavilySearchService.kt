package me.rerere.fawntavern.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.fawntavern.data.api.Http
import me.rerere.fawntavern.data.search.SearchResult.SearchResultItem
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Tavily 搜索 API（api.tavily.com），需 API key */
object TavilySearchService : SearchService<SearchServiceOptions.TavilyOptions> {
    override val name: String = "Tavily"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TavilyOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("query", query)
                put("max_results", commonOptions.resultSize)
                put("search_depth", serviceOptions.depth.ifEmpty { "advanced" })
                put("topic", "general")
                put("include_answer", "advanced")
            }
            val request = Request.Builder()
                .url("https://api.tavily.com/search")
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()
            val response = Http.client.newCall(request).await()
            if (!response.isSuccessful) error("response failed #${response.code}")
            val resp = searchJson.decodeFromString<SearchResponse>(response.body.string())
            SearchResult(
                answer = resp.answer,
                items = resp.results.map { SearchResultItem(it.title, it.url, it.content) },
            )
        }
    }

    @Serializable
    data class SearchResponse(
        val answer: String? = null,
        val results: List<SearchResultItemDto>,
    )

    @Serializable
    data class SearchResultItemDto(
        val title: String,
        val url: String,
        val content: String,
    )
}
