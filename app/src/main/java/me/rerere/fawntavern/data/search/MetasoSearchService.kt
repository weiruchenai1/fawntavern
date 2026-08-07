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

/** 秘塔搜索 API（metaso.cn），需 API key */
object MetasoSearchService : SearchService<SearchServiceOptions.MetasoOptions> {
    override val name: String = "Metaso（秘塔）"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.MetasoOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("q", query)
                put("scope", "webpage")
                put("size", commonOptions.resultSize)
                put("includeSummary", false)
            }
            val request = Request.Builder()
                .url("https://metaso.cn/api/v1/search")
                .post(searchJson.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .addHeader("Accept", "application/json")
                .build()
            val response = Http.client.newCall(request).await()
            if (!response.isSuccessful) error("Metaso search failed with code ${response.code}")
            val resp = searchJson.decodeFromString<MetasoResponse>(response.body.string())
            SearchResult(
                items = resp.webpages.take(commonOptions.resultSize)
                    .map { SearchResultItem(it.title, it.link, it.snippet ?: "") },
            )
        }
    }

    @Serializable
    data class MetasoResponse(val webpages: List<MetasoWebpage> = emptyList())

    @Serializable
    data class MetasoWebpage(
        val title: String,
        val link: String,
        val snippet: String? = null,
    )
}
