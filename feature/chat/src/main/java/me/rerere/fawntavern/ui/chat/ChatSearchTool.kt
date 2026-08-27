package me.rerere.fawntavern.ui.chat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.rerere.fawntavern.data.api.ApiToolCall
import me.rerere.fawntavern.data.api.ToolSpec
import me.rerere.fawntavern.data.chat.MsgSearch
import me.rerere.fawntavern.domain.GenerationToolExecutor
import org.json.JSONObject

interface ChatSearchToolDataSource {
    fun providerName(): String
    suspend fun search(query: String): Pair<String, MsgSearch>
}

/** 生成流程使用的联网搜索工具声明与执行器。 */
class ChatSearchTool(private val dataSource: ChatSearchToolDataSource) {
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

    fun executor(): GenerationToolExecutor = object : GenerationToolExecutor {
        override fun describe(call: ApiToolCall): MsgSearch? {
            if (call.name != "search_web") return null
            val query = queryOf(call)
            if (query.isBlank()) return null
            return MsgSearch(query = query, provider = dataSource.providerName(), searching = true)
        }

        override suspend fun execute(call: ApiToolCall): Pair<String, MsgSearch?> {
            if (call.name != "search_web") {
                return JSONObject().put("error", "unknown tool").toString() to null
            }
            val query = queryOf(call)
            if (query.isBlank()) {
                return JSONObject().put("error", "missing query").toString() to null
            }
            return dataSource.search(query)
        }
    }

    private fun queryOf(call: ApiToolCall): String =
        runCatching { JSONObject(call.arguments).optString("query") }.getOrDefault("").trim()
}
