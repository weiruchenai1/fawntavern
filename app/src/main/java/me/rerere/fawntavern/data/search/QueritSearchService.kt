package me.rerere.fawntavern.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.fawntavern.data.api.Http
import me.rerere.fawntavern.data.search.SearchResult.SearchResultItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Querit 搜索 API（api.querit.ai），需 API key；支持站点包含/排除、时间范围、国家、语言过滤 */
object QueritSearchService : SearchService<SearchServiceOptions.QueritOptions> {
    override val name: String = "Querit"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.QueritOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = serviceOptions.apiKey.trim()
            require(apiKey.isNotEmpty()) { "Querit API key 不能为空" }
            val body = buildJsonObject {
                put("query", query)
                put("count", commonOptions.resultSize)
                buildFilters(serviceOptions).takeIf { it.isNotEmpty() }?.let { put("filters", it) }
            }
            val request = Request.Builder()
                .url("https://api.querit.ai/v1/search")
                .post(searchJson.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            val response = Http.client.newCall(request).await()
            if (!response.isSuccessful) error("Querit search failed with code ${response.code}")
            val resp = searchJson.decodeFromString<QueritResponse>(response.body.string())
            resp.errorCode?.let { if (it != 200) error("Querit API error: $it ${resp.errorMsg.orEmpty()}") }
            val items = resp.results?.result.orEmpty().take(commonOptions.resultSize).map(::resultItem)
            SearchResult(items = items)
        }
    }

    private fun buildFilters(o: SearchServiceOptions.QueritOptions): JsonObject {
        val filters = mutableListOf<Pair<String, JsonObject>>()
        val sitesInclude = splitList(o.sitesInclude)
        val sitesExclude = splitList(o.sitesExclude)
        if (sitesInclude.isNotEmpty() || sitesExclude.isNotEmpty()) {
            filters += "sites" to buildJsonObject {
                if (sitesInclude.isNotEmpty()) put("include", buildJsonArray { sitesInclude.forEach { add(it) } })
                if (sitesExclude.isNotEmpty()) put("exclude", buildJsonArray { sitesExclude.forEach { add(it) } })
            }
        }
        val timeRange = o.timeRange.trim()
        if (timeRange.isNotEmpty()) {
            filters += "timeRange" to buildJsonObject { put("date", timeRange) }
        }
        val countries = splitList(o.countries)
        if (countries.isNotEmpty()) {
            filters += "geo" to buildJsonObject {
                put("countries", buildJsonObject {
                    put("include", buildJsonArray { countries.forEach { add(it) } })
                })
            }
        }
        val languages = splitList(o.languages)
        if (languages.isNotEmpty()) {
            filters += "languages" to buildJsonObject {
                put("include", buildJsonArray { languages.forEach { add(it) } })
            }
        }
        return buildJsonObject { filters.forEach { (k, v) -> put(k, v) } }
    }

    private fun splitList(value: String): List<String> =
        value.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun resultItem(it: QueritItem): SearchResultItem {
        val snippet = it.snippet.orEmpty().trim()
        val sourceSnippets = (it.snippets ?: it.sentence ?: emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != snippet }
        val text = (listOf(snippet).filter { it.isNotEmpty() } + sourceSnippets).joinToString("\n\n")
        val url = it.url.orEmpty()
        val title = it.title.orEmpty().trim()
        return SearchResultItem(title = title.ifEmpty { url }, url = url, text = text)
    }

    @Serializable
    data class QueritResponse(
        @SerialName("error_code") val errorCode: Int? = null,
        @SerialName("error_msg") val errorMsg: String? = null,
        val results: QueritResults? = null,
    )

    @Serializable
    data class QueritResults(val result: List<QueritItem>? = null)

    @Serializable
    data class QueritItem(
        val title: String? = null,
        val url: String? = null,
        val snippet: String? = null,
        val snippets: List<String>? = null,
        val sentence: List<String>? = null,
    )
}
