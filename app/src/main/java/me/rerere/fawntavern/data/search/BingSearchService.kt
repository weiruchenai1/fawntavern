package me.rerere.fawntavern.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.search.SearchResult.SearchResultItem
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale

/** Bing 网页搜索（爬虫解析，无需 API key，可开箱即用） */
object BingSearchService : SearchService<SearchServiceOptions.BingLocalOptions> {
    override val name: String = "Bing"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://www.bing.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
            val locale = Locale.getDefault()
            val acceptLanguage = "${locale.language}-${locale.country},${locale.language}"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", acceptLanguage)
                .referrer("https://www.bing.com/")
                .timeout(5000)
                .get()
            val items = doc.select("li.b_algo").map { el ->
                SearchResultItem(
                    title = el.select("h2").text(),
                    url = el.select("h2 > a").attr("href"),
                    text = el.select(".b_caption p").text(),
                )
            }
            require(items.isNotEmpty()) { "搜索失败：未找到结果" }
            SearchResult(items = items.take(commonOptions.resultSize))
        }
    }
}
