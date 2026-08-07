package me.rerere.fawntavern.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.fawntavern.data.api.Http
import me.rerere.fawntavern.data.search.SearchResult.SearchResultItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Perplexity 搜索 API（api.perplexity.ai/search），需 API key；单查询/多查询两种返回形状都兼容 */
object PerplexitySearchService : SearchService<SearchServiceOptions.PerplexityOptions> {
    override val name: String = "Perplexity"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.PerplexityOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("query", query)
                put("max_results", commonOptions.resultSize.coerceIn(1, 20))
            }
            val request = Request.Builder()
                .url("https://api.perplexity.ai/search")
                .post(searchJson.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()
            val response = Http.client.newCall(request).await()
            if (!response.isSuccessful) error("Perplexity search failed with code ${response.code}")
            val resp = searchJson.decodeFromString<PerplexityResponse>(response.body.string())
            val flat = mutableListOf<JsonObject>()
            for (el in resp.results) {
                val arr = el.asArray()
                if (arr != null) arr.forEach { sub -> sub.asObject()?.let(flat::add) }
                else el.asObject()?.let(flat::add)
            }
            SearchResult(
                items = flat.take(commonOptions.resultSize)
                    .map { SearchResultItem(it["title"].asText(), it["url"].asText(), it["snippet"].asText()) },
            )
        }
    }

    @Serializable
    data class PerplexityResponse(val results: List<JsonElement> = emptyList())
}
