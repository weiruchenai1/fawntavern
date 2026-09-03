package me.rerere.fawntavern.data.search

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okio.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 统一的联网搜索执行接口。 */
interface SearchService<T : SearchServiceOptions> {
    val name: String

    suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: T,
    ): Result<SearchResult>
}

/** 按配置取对应服务实现。 */
@Suppress("UNCHECKED_CAST")
fun createSearchService(options: SearchServiceOptions): SearchService<SearchServiceOptions> {
    return when (options.key) {
        "tavily" -> TavilySearchService
        "exa" -> ExaSearchService
        "zhipu" -> ZhipuSearchService
        "searxng" -> SearXNGService
        "brave" -> BraveSearchService
        "duckduckgo" -> DuckDuckGoSearchService
        "linkup" -> LinkUpSearchService
        "metaso" -> MetasoSearchService
        "ollama" -> OllamaSearchService
        "jina" -> JinaSearchService
        "bocha" -> BochaSearchService
        "perplexity" -> PerplexitySearchService
        "serper" -> SerperSearchService
        "querit" -> QueritSearchService
        "grok" -> GrokSearchService
        else -> BingSearchService
    } as SearchService<SearchServiceOptions>
}

internal val searchJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response)
        }
    })
    continuation.invokeOnCancellation { cancel() }
}

internal fun JsonElement?.asText(): String = when (this) {
    is JsonPrimitive -> if (this == JsonNull) "" else content
    else -> ""
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
