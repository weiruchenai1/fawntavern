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

/** Search tool contract and executor used by the generation pipeline. */
internal class ChatSearchTool(private val context: Context) {
    fun spec(): ToolSpec {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return ToolSpec(
            name = "search_web",
            description = """
                Search the web for up-to-date or specific information.
                Use this when the conversation needs current facts, news, or verification.
                Think first, then distill focused search keywords yourself - do NOT copy the user's message verbatim.
                If results are insufficient, refine the keywords and search again (multiple calls allowed).
                Today is $today.
            """.trimIndent(),
            parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"Focused search keywords"}},"required":["query"]}""",
        )
    }

    fun executor(): GenerationController.ToolExecutor = object : GenerationController.ToolExecutor {
        override fun describe(call: ApiToolCall): MsgSearch? {
            if (call.name != "search_web") return null
            val query = queryOf(call)
            if (query.isBlank()) return null
            return MsgSearch(query = query, provider = SearchStore.getSelected(context).displayName, searching = true)
        }

        override suspend fun execute(call: ApiToolCall): Pair<String, MsgSearch?> {
            if (call.name != "search_web") return """{"error":"unknown tool"}""" to null
            val query = queryOf(call)
            if (query.isBlank()) return """{"error":"missing query"}""" to null
            val options = SearchStore.getSelected(context)
            val result = createSearchService(options)
                .search(query, SearchCommonOptions(SearchStore.getResultSize(context)), options)
                .getOrThrow()
            val items = JSONArray()
            result.items.forEachIndexed { index, item ->
                items.put(JSONObject().put("index", index + 1).put("title", item.title)
                    .put("url", item.url).put("content", item.text))
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

    private fun queryOf(call: ApiToolCall): String =
        runCatching { JSONObject(call.arguments).optString("query") }.getOrDefault("").trim()
}
