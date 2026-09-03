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

/** Exa 搜索 API（api.exa.ai），需 API key；输出可选携带 AI 摘要（output.content） */
object ExaSearchService : SearchService<SearchServiceOptions.ExaOptions> {
    override val name: String = "Exa"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("query", query)
                put("numResults", commonOptions.resultSize)
                put("type", "auto")
                put("contents", buildJsonObject { put("text", true) })
            }
            val request = Request.Builder()
                .url("https://api.exa.ai/search")
                .post(searchJson.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()
            val response = Http.client.newCall(request).await()
            if (!response.isSuccessful) error("response failed #${response.code}")
            val resp = searchJson.decodeFromString<ExaData>(response.body.string())
            SearchResult(
                answer = resp.output?.content,
                items = resp.results.map { SearchResultItem(it.title, it.url, it.text ?: "") },
            )
        }
    }

    @Serializable
    data class ExaData(
        val results: List<ExaResult>,
        val output: ExaOutput? = null,
    )

    @Serializable
    data class ExaOutput(val content: String? = null)

    @Serializable
    data class ExaResult(
        val title: String,
        val url: String,
        @SerialName("text") val text: String? = null,
    )
}
