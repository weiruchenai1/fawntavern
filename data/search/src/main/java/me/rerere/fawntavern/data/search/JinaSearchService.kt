package me.rerere.fawntavern.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.fawntavern.data.api.Http
import me.rerere.fawntavern.data.search.SearchResult.SearchResultItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Jina Reader / s.jina.ai 搜索，需 API key；响应形状不稳定，用容错解析 */
object JinaSearchService : SearchService<SearchServiceOptions.JinaOptions> {
    override val name: String = "Jina"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.JinaOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject { put("q", query) }
            val request = Request.Builder()
                .url("https://s.jina.ai/")
                .post(searchJson.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .addHeader("Accept", "application/json")
                .build()
            val response = Http.client.newCall(request).await()
            if (!response.isSuccessful) error("Jina search failed with code ${response.code}")
            val root = searchJson.parseToJsonElement(response.body.string()).jsonObject
            val list = root["data"].asArray() ?: root["results"].asArray() ?: JsonArray(emptyList())
            val items = list.take(commonOptions.resultSize).mapNotNull { el ->
                val o = el.asObject() ?: return@mapNotNull null
                SearchResultItem(o["title"].asText(), o["url"].asText(), o["description"].asText())
            }
            SearchResult(items = items)
        }
    }
}
