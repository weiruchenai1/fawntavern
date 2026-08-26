package me.rerere.fawntavern.ui.chat

import android.content.Context
import me.rerere.fawntavern.data.api.ApiToolCall
import me.rerere.fawntavern.data.api.ToolSpec
import me.rerere.fawntavern.data.chat.MsgSearch
import me.rerere.fawntavern.data.chat.SearchCitation
import me.rerere.fawntavern.data.search.SearchCommonOptions
import me.rerere.fawntavern.data.search.createSearchService
import me.rerere.fawntavern.data.settings.SearchStore
import me.rerere.fawntavern.domain.GenerationController
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal interface ChatSearchToolDataSource {
    fun providerName(): String
    suspend fun search(query: String): Pair<String, MsgSearch>
}

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

/** 生成流程使用的联网搜索工具声明与执行器。 */
internal class ChatSearchTool(private val dataSource: ChatSearchToolDataSource) {
    fun spec(): ToolSpec {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return ToolSpec(
            name = "search_web",
            description = "Search the web for current, uncertain, or externally verifiable information. " +
                "Use a concise query while preserving exact names, quoted text, identifiers, and error codes. " +
                "Refine the query and search again only if the results are insufficient. " +
                "Current date: $today.",
            parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"Concise web search query; preserve exact names, quoted text, identifiers, and error codes"}},"required":["query"]}""",
        )
    }

    fun executor(): GenerationController.ToolExecutor = object : GenerationController.ToolExecutor {
        override fun describe(call: ApiToolCall): MsgSearch? {
            if (call.name != "search_web") return null
            val query = queryOf(call)
            if (query.isBlank()) return null
            return MsgSearch(query = query, provider = dataSource.providerName(), searching = true)
        }

        override suspend fun execute(call: ApiToolCall): Pair<String, MsgSearch?> {
            if (call.name != "search_web") return JSONObject().put("error", "unknown tool").toString() to null
            val query = queryOf(call)
            if (query.isBlank()) return JSONObject().put("error", "missing query").toString() to null
            return dataSource.search(query)
        }
    }

    private fun queryOf(call: ApiToolCall): String =
        runCatching { JSONObject(call.arguments).optString("query") }.getOrDefault("").trim()
}
