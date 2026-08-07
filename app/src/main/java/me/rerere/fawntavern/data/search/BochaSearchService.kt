package me.rerere.fawntavern.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.fawntavern.data.api.Http
import me.rerere.fawntavern.data.search.SearchResult.SearchResultItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Bocha 博查搜索 API（api.bochaai.com/v1/web-search），需 API key */
object BochaSearchService : SearchService<SearchServiceOptions.BochaOptions> {
    override val name: String = "Bocha"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BochaOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("query", query)
                put("count", commonOptions.resultSize)
                put("summary", true)
            }
            val request = Request.Builder()
                .url("https://api.bochaai.com/v1/web-search")
                .post(searchJson.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()
            val response = Http.client.newCall(request).await()
            if (!response.isSuccessful) error("Bocha search failed with code ${response.code}")
            val resp = searchJson.decodeFromString<BochaResponse>(response.body.string())
            resp.code?.let { if (it != 200) error("Bocha API error code: $it") }
            SearchResult(
                items = resp.data?.webPages?.value.orEmpty().take(commonOptions.resultSize)
                    .map { SearchResultItem(it.name, it.url, (it.summary ?: it.snippet).orEmpty()) },
            )
        }
    }

    @Serializable
    data class BochaResponse(val code: Int? = null, val data: BochaData? = null)

    @Serializable
    data class BochaData(@SerialName("webPages") val webPages: BochaWebPages? = null)

    @Serializable
    data class BochaWebPages(val value: List<BochaItem>? = null)

    @Serializable
    data class BochaItem(
        val name: String,
        val url: String,
        val summary: String? = null,
        val snippet: String? = null,
    )
}
