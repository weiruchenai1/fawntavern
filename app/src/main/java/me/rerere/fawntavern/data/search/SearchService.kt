package me.rerere.fawntavern.data.search

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.fawntavern.data.api.Http
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okio.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 联网搜索服务。
 *
 * 每个 provider 是一个无状态的 Kotlin `object`（无 DI），复用全局 [Http.client]。
 * 搜索以 search_web 函数工具暴露给模型（关键词由模型决定），App 负责执行并回传结果，
 * 故接口只保留按 query 搜索。
 */
interface SearchService<T : SearchServiceOptions> {
    val name: String

    suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: T,
    ): Result<SearchResult>
}

/** 搜索结果数量等通用参数（Provider 共用的配置） */
data class SearchCommonOptions(
    val resultSize: Int = 10,
)

/** 统一搜索结果：items 为条目列表，answer 为部分 provider 给的摘要 */
data class SearchResult(
    val answer: String? = null,
    val items: List<SearchResultItem>,
) {
    data class SearchResultItem(
        val title: String,
        val url: String,
        val text: String,
    )
}

/**
 * Provider 配置（无 @Serializable —— 持久化走 SearchStore 的 org.json 手写序列化）。
 * [key] 是存储与分发的唯一标识（对应 [SearchServiceOptions.Companion.ALL]）。
 */
sealed class SearchServiceOptions {
    abstract val key: String
    abstract val displayName: String

    /** 实例唯一标识：同一类型的多个配置（如两个 Tavily）靠 id 区分，列表 key 与选中定位都用它 */
    abstract val id: String

    /** 免配置即可用的零配置 provider（Bing 爬虫） */
    class BingLocalOptions(override val id: String = newSearchId()) : SearchServiceOptions() {
        override val key = "bing"
        override val displayName = "Bing"
    }

    data class TavilyOptions(
        override val id: String = newSearchId(),
        var apiKey: String = "",
        var depth: String = "advanced",
    ) : SearchServiceOptions() {
        override val key = "tavily"
        override val displayName = "Tavily"
    }

    data class ExaOptions(
        override val id: String = newSearchId(),
        var apiKey: String = "",
    ) : SearchServiceOptions() {
        override val key = "exa"
        override val displayName = "Exa"
    }

    data class ZhipuOptions(
        override val id: String = newSearchId(),
        var apiKey: String = "",
    ) : SearchServiceOptions() {
        override val key = "zhipu"
        override val displayName = "智谱"
    }

    data class SearXNGOptions(
        override val id: String = newSearchId(),
        var url: String = "",
        var engines: String = "",
        var language: String = "",
        var username: String = "",
        var password: String = "",
    ) : SearchServiceOptions() {
        override val key = "searxng"
        override val displayName = "SearXNG"
    }

    data class BraveOptions(
        override val id: String = newSearchId(),
        var apiKey: String = "",
    ) : SearchServiceOptions() {
        override val key = "brave"
        override val displayName = "Brave"
    }

    data class DuckDuckGoOptions(
        override val id: String = newSearchId(),
        var region: String = "us-en",
    ) : SearchServiceOptions() {
        override val key = "duckduckgo"
        override val displayName = "DuckDuckGo"
    }

    data class LinkUpOptions(
        override val id: String = newSearchId(),
        var apiKey: String = "",
    ) : SearchServiceOptions() {
        override val key = "linkup"
        override val displayName = "LinkUp"
    }

    data class MetasoOptions(
        override val id: String = newSearchId(),
        var apiKey: String = "",
    ) : SearchServiceOptions() {
        override val key = "metaso"
        override val displayName = "Metaso（秘塔）"
    }

    data class OllamaOptions(
        override val id: String = newSearchId(),
        var apiKey: String = "",
    ) : SearchServiceOptions() {
        override val key = "ollama"
        override val displayName = "Ollama"
    }

    data class JinaOptions(
        override val id: String = newSearchId(),
        var apiKey: String = "",
    ) : SearchServiceOptions() {
        override val key = "jina"
        override val displayName = "Jina"
    }

    data class BochaOptions(
        override val id: String = newSearchId(),
        var apiKey: String = "",
    ) : SearchServiceOptions() {
        override val key = "bocha"
        override val displayName = "Bocha"
    }

    data class PerplexityOptions(
        override val id: String = newSearchId(),
        var apiKey: String = "",
    ) : SearchServiceOptions() {
        override val key = "perplexity"
        override val displayName = "Perplexity"
    }

    data class SerperOptions(
        override val id: String = newSearchId(),
        var apiKey: String = "",
        var gl: String = "",
        var hl: String = "",
        var tbs: String = "",
        var page: Int = 1,
    ) : SearchServiceOptions() {
        override val key = "serper"
        override val displayName = "Serper"
    }

    data class QueritOptions(
        override val id: String = newSearchId(),
        var apiKey: String = "",
        var sitesInclude: String = "",
        var sitesExclude: String = "",
        var timeRange: String = "",
        var countries: String = "",
        var languages: String = "",
    ) : SearchServiceOptions() {
        override val key = "querit"
        override val displayName = "Querit"
    }

    data class GrokOptions(
        override val id: String = newSearchId(),
        var apiKey: String = "",
        var model: String = defaultModel,
        var reasoningEffort: String = defaultReasoningEffort,
        var customUrl: String = defaultUrl,
        var systemPrompt: String = defaultSystemPrompt,
    ) : SearchServiceOptions() {
        override val key = "grok"
        override val displayName = "Grok"

        val resolvedUrl: String get() = customUrl.trim().ifEmpty { defaultUrl }
        val resolvedModel: String get() = model.trim().ifEmpty { defaultModel }
        val resolvedReasoningEffort: String get() = reasoningEffort.trim()
        val resolvedSystemPrompt: String get() = systemPrompt.trim().ifEmpty { defaultSystemPrompt }

        companion object {
            const val defaultUrl = "https://api.x.ai/v1/responses"
            const val defaultModel = "grok-4.5"
            const val defaultReasoningEffort = "low"
            const val defaultSystemPrompt = "You are a helpful search assistant. Search the web to find accurate and up-to-date information for the user's query. Provide a comprehensive answer with citations."
        }
    }

    companion object {
        /** 全部可选 provider 类型，配置页「添加」底部面板的预设列表 */
        val ALL: List<SearchServiceOptions> = listOf(
            BingLocalOptions(), TavilyOptions(), ExaOptions(), ZhipuOptions(), SearXNGOptions(), BraveOptions(),
            DuckDuckGoOptions(), LinkUpOptions(), MetasoOptions(), OllamaOptions(), JinaOptions(),
            BochaOptions(), PerplexityOptions(), SerperOptions(), QueritOptions(), GrokOptions(),
        )

        /** 按类型 key 建全新实例（每次调用生成新 id），供「添加提供商」使用 */
        fun fromKey(key: String, id: String = newSearchId()): SearchServiceOptions = when (key) {
            "tavily" -> TavilyOptions(id = id)
            "exa" -> ExaOptions(id = id)
            "zhipu" -> ZhipuOptions(id = id)
            "searxng" -> SearXNGOptions(id = id)
            "brave" -> BraveOptions(id = id)
            "duckduckgo" -> DuckDuckGoOptions(id = id)
            "linkup" -> LinkUpOptions(id = id)
            "metaso" -> MetasoOptions(id = id)
            "ollama" -> OllamaOptions(id = id)
            "jina" -> JinaOptions(id = id)
            "bocha" -> BochaOptions(id = id)
            "perplexity" -> PerplexityOptions(id = id)
            "serper" -> SerperOptions(id = id)
            "querit" -> QueritOptions(id = id)
            "grok" -> GrokOptions(id = id)
            else -> BingLocalOptions(id = id)
        }
    }
}

/** 生成新提供商实例 id（UUID 文本，同一类型可配置多实例） */
fun newSearchId(): String = java.util.UUID.randomUUID().toString()

/** 按配置取对应服务实现（静态分发，无 DI） */
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

/** Provider 共用的 Json 实例（容错解码 provider 返回） */
internal val searchJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** 异步执行 OkHttp 请求：挂起直到回调，协程取消时同步取消请求 */
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

/** 容错取 Json 元素文本：非字符串/数字原始值或缺失一律返回空串（用于解析形状不稳定的 provider 响应） */
internal fun JsonElement?.asText(): String = when (this) {
    is JsonPrimitive -> if (this == JsonNull) "" else content
    else -> ""
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
