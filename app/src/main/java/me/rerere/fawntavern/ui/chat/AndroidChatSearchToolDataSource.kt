package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.data.chat.MsgSearch
import me.rerere.fawntavern.data.chat.SearchCitation
import me.rerere.fawntavern.data.search.SearchCommonOptions
import me.rerere.fawntavern.data.search.createSearchService
import me.rerere.fawntavern.data.settings.SearchStore
import org.json.JSONArray
import org.json.JSONObject

internal class AndroidChatSearchToolDataSource(
    private val context: Context,
) : ChatSearchToolDataSource {
    override fun providerName(): String = SearchStore.getSelected(context).displayName

    override suspend fun search(query: String): Pair<String, MsgSearch> {
        val options = SearchStore.getSelected(context)
        val result = createSearchService(options)
            .search(query, SearchCommonOptions(SearchStore.getResultSize(context)), options)
            .getOrThrow()
        val items = JSONArray()
        result.items.forEachIndexed { index, item ->
            items.put(
                JSONObject().put("index", index + 1).put("title", item.title)
                    .put("url", item.url).put("content", item.text),
            )
        }
        val payload = JSONObject().apply {
            put("query", query)
            result.answer?.takeIf { it.isNotBlank() }?.let { put("answer", it) }
            put("items", items)
        }
        return payload.toString() to MsgSearch(
            query = query,
            provider = options.displayName,
            items = result.items.map { SearchCitation(it.title, it.url, it.text) },
        )
    }
}
