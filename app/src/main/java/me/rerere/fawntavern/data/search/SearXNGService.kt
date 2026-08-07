package me.rerere.fawntavern.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.fawntavern.data.api.Http
import me.rerere.fawntavern.data.search.SearchResult.SearchResultItem
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.URLEncoder

/** SearXNG（自托管元搜索引擎）JSON API，需自建实例；支持 Basic Auth 与 engines/language 参数 */
object SearXNGService : SearchService<SearchServiceOptions.SearXNGOptions> {
    override val name: String = "SearXNG"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SearXNGOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(serviceOptions.url.isNotBlank()) { "SearXNG 地址不能为空" }
            val baseUrl = serviceOptions.url.trimEnd('/')
            val url = "$baseUrl/search?q=${URLEncoder.encode(query, "UTF-8")}&format=json"
                .toHttpUrl().newBuilder().apply {
                    if (serviceOptions.engines.isNotBlank()) addQueryParameter("engines", serviceOptions.engines)
                    if (serviceOptions.language.isNotBlank()) addQueryParameter("language", serviceOptions.language)
                }.build()
            val request = Request.Builder()
                .url(url).get()
                .apply {
                    if (serviceOptions.username.isNotBlank() && serviceOptions.password.isNotBlank()) {
                        header("Authorization", Credentials.basic(serviceOptions.username, serviceOptions.password))
                    }
                }.build()
            val response = Http.client.newCall(request).await()
            if (!response.isSuccessful) error("SearXNG request failed with status ${response.code}")
            val resp = searchJson.decodeFromString<SearXNGResponse>(response.body.string())
            SearchResult(
                items = resp.results.take(commonOptions.resultSize)
                    .map { SearchResultItem(it.title, it.url, it.content) },
            )
        }
    }

    @Serializable
    data class SearXNGResponse(val results: List<SearXNGResult>)

    @Serializable
    data class SearXNGResult(
        val url: String,
        val title: String,
        val content: String,
    )
}
