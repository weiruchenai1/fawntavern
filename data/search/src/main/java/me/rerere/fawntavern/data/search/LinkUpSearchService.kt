package me.rerere.fawntavern.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.fawntavern.data.api.Http
import me.rerere.fawntavern.data.search.SearchResult.SearchResultItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** LinkUp 搜索 API（api.linkup.so），需 API key */
object LinkUpSearchService : SearchService<SearchServiceOptions.LinkUpOptions> {
    override val name: String = "LinkUp"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.LinkUpOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("q", query)
                put("depth", "standard")
                put("outputType", "sourcedAnswer")
                put("includeImages", "false")
            }
            val request = Request.Builder()
                .url("https://api.linkup.so/v1/search")
                .post(searchJson.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()
            val response = Http.client.newCall(request).await()
            if (!response.isSuccessful) error("LinkUp search failed with code ${response.code}")
            val resp = searchJson.decodeFromString<LinkUpResponse>(response.body.string())
            SearchResult(
                answer = resp.answer,
                items = resp.sources.take(commonOptions.resultSize)
                    .map { SearchResultItem(it.name, it.url, it.snippet ?: "") },
            )
        }
    }

    @Serializable
    data class LinkUpResponse(
        val answer: String? = null,
        val sources: List<LinkUpSource> = emptyList(),
    )

    @Serializable
    data class LinkUpSource(
        val name: String,
        val url: String,
        val snippet: String? = null,
    )
}
