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

/** 智谱 web_search API（open.bigmodel.cn），需智谱 API key */
object ZhipuSearchService : SearchService<SearchServiceOptions.ZhipuOptions> {
    override val name: String = "智谱"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ZhipuOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("search_query", query)
                put("search_engine", "search_std")
                put("count", commonOptions.resultSize)
            }
            val request = Request.Builder()
                .url("https://open.bigmodel.cn/api/paas/v4/web_search")
                .post(searchJson.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()
            val response = Http.client.newCall(request).await()
            if (!response.isSuccessful) error("response failed #${response.code}")
            val resp = searchJson.decodeFromString<ZhipuDto>(response.body.string())
            SearchResult(
                items = resp.searchResult.map { SearchResultItem(it.title, it.link, it.content) },
            )
        }
    }

    @Serializable
    data class ZhipuDto(
        @SerialName("search_result") val searchResult: List<ZhipuResultDto>,
    )

    @Serializable
    data class ZhipuResultDto(
        val title: String,
        val link: String,
        val content: String,
    )
}
