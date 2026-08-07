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

/** Serper（Google 搜索 API 代理，google.serper.dev），需 API key */
object SerperSearchService : SearchService<SearchServiceOptions.SerperOptions> {
    override val name: String = "Serper"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SerperOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("q", query)
                serviceOptions.gl.trim().takeIf { it.isNotEmpty() }?.let { put("gl", it) }
                serviceOptions.hl.trim().takeIf { it.isNotEmpty() }?.let { put("hl", it) }
                serviceOptions.tbs.trim().takeIf { it.isNotEmpty() }?.let { put("tbs", it) }
                if (serviceOptions.page > 1) put("page", serviceOptions.page)
            }
            val request = Request.Builder()
                .url("https://google.serper.dev/search")
                .post(searchJson.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("X-API-KEY", serviceOptions.apiKey)
                .build()
            val response = Http.client.newCall(request).await()
            if (!response.isSuccessful) error("Serper search failed with code ${response.code}")
            val resp = searchJson.decodeFromString<SerperResponse>(response.body.string())
            SearchResult(
                items = resp.organic.orEmpty().take(commonOptions.resultSize)
                    .map { SearchResultItem(it.title, it.link, it.snippet ?: "") },
            )
        }
    }

    @Serializable
    data class SerperResponse(val organic: List<OrganicItem>? = null)

    @Serializable
    data class OrganicItem(
        val title: String,
        val link: String,
        val snippet: String? = null,
    )
}
