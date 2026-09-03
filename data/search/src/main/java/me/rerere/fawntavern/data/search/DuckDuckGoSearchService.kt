package me.rerere.fawntavern.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.search.SearchResult.SearchResultItem
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

/** DuckDuckGo 网页搜索（爬 html.duckduckgo.com，无需 API key；region 用 kl 参数） */
object DuckDuckGoSearchService : SearchService<SearchServiceOptions.DuckDuckGoOptions> {
    override val name: String = "DuckDuckGo"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val region = serviceOptions.region.trim().ifEmpty { "us-en" }
            val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(query, "UTF-8")}" +
                "&kl=${URLEncoder.encode(region, "UTF-8")}"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .timeout(8000)
                .get()
            val items = doc.select(".result").mapNotNull { el ->
                val anchor = el.selectFirst(".result__a") ?: return@mapNotNull null
                val title = anchor.text()
                val url = decodeRedirect(anchor.attr("href"))
                if (title.isEmpty() && url.isEmpty()) return@mapNotNull null
                SearchResultItem(title, url, el.select(".result__snippet").text())
            }
            require(items.isNotEmpty()) { "搜索失败：未找到结果" }
            SearchResult(items = items.take(commonOptions.resultSize))
        }
    }

    /** DDG 结果链接是 /l/?uddg= 重定向，需解码出真实 URL */
    private fun decodeRedirect(href: String): String {
        if (!href.contains("uddg=")) return href
        val encoded = href.substringAfter("uddg=").substringBefore('&')
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(href)
    }
}
