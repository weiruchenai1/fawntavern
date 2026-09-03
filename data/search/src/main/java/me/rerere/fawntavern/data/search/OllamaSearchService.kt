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

/** Ollama 网页搜索 API（ollama.com/api/web_search），需 API key */
object OllamaSearchService : SearchService<SearchServiceOptions.OllamaOptions> {
    override val name: String = "Ollama"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.OllamaOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("query", query)
                put("max_results", commonOptions.resultSize.coerceIn(1, 10))
            }
            val request = Request.Builder()
                .url("https://ollama.com/api/web_search")
                .post(searchJson.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()
            val response = Http.client.newCall(request).await()
            if (!response.isSuccessful) error("Ollama search failed with code ${response.code}")
            val resp = searchJson.decodeFromString<OllamaResponse>(response.body.string())
            SearchResult(
                items = resp.results.take(commonOptions.resultSize)
                    .map { SearchResultItem(it.title, it.url, it.content ?: "") },
            )
        }
    }

    @Serializable
    data class OllamaResponse(val results: List<OllamaResult> = emptyList())

    @Serializable
    data class OllamaResult(
        val title: String,
        val url: String,
        val content: String? = null,
    )
}
