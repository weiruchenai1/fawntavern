package me.rerere.fawntavern.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.fawntavern.data.api.Http
import me.rerere.fawntavern.data.search.SearchResult.SearchResultItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Grok 联网搜索：走 x.ai Responses API，让模型带 web_search / x_search 工具作答，
 * 再把返回的引用（顶层 citations 与正文内 annotations）转成结果条目。
 */
object GrokSearchService : SearchService<SearchServiceOptions.GrokOptions> {
    override val name: String = "Grok"

    override suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.GrokOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(serviceOptions.apiKey.trim().isNotEmpty()) { "Grok API key 不能为空" }
            val body = buildJsonObject {
                put("model", serviceOptions.resolvedModel)
                put("input", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", serviceOptions.resolvedSystemPrompt) })
                    add(buildJsonObject { put("role", "user"); put("content", query) })
                })
                put("tools", buildJsonArray {
                    add(buildJsonObject { put("type", "web_search") })
                    add(buildJsonObject { put("type", "x_search") })
                })
                put("store", false)
                put("stream", false)
                serviceOptions.resolvedReasoningEffort.takeIf { it.isNotEmpty() }?.let {
                    put("reasoning", buildJsonObject { put("effort", it) })
                }
            }
            val request = Request.Builder()
                .url(serviceOptions.resolvedUrl)
                .post(searchJson.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey.trim()}")
                .build()
            val response = Http.client.newCall(request).await()
            if (!response.isSuccessful) error("Grok search failed with code ${response.code}")
            val root = searchJson.parseToJsonElement(response.body.string()).jsonObject

            val output = root["output"].asArray() ?: JsonArray(emptyList())
            val message = output.firstOrNull {
                it.asObject()?.get("type")?.asText() == "message" && it.asObject()?.get("role")?.asText() == "assistant"
            }?.asObject()
            val content = message?.get("content")?.asArray() ?: JsonArray(emptyList())
            val textContent = content.firstOrNull { it.asObject()?.get("type")?.asText() == "output_text" }?.asObject()

            val items = mutableListOf<SearchResultItem>()
            addCitationItems(items, root["citations"], commonOptions.resultSize)
            if (items.size < commonOptions.resultSize) {
                addCitationItems(items, textContent?.get("annotations"), commonOptions.resultSize)
            }
            val answer = textContent?.get("text").asText()
            SearchResult(answer = answer.takeIf { it.isNotEmpty() }, items = items)
        }
    }

    private fun addCitationItems(
        items: MutableList<SearchResultItem>,
        citations: JsonElement?,
        maxItems: Int,
    ) {
        val seen = items.map { it.url }.toHashSet()
        for (citation in citations.asArray() ?: JsonArray(emptyList())) {
            val item = citationItem(citation) ?: continue
            if (!seen.add(item.url)) continue
            items.add(item)
            if (items.size >= maxItems) return
        }
    }

    private fun citationItem(citation: JsonElement): SearchResultItem? {
        val asStr = citation.asText()
        if (asStr.isNotEmpty()) {
            val url = asStr.trim()
            if (url.isEmpty()) return null
            return SearchResultItem(url, url, "")
        }
        val o = citation.asObject() ?: return null
        if (o["type"].asText() != "url_citation") return null
        val url = o["url"].asText().trim()
        if (url.isEmpty()) return null
        val title = o["title"].asText().trim()
        return SearchResultItem(title = title.ifEmpty { url }, url = url, text = "")
    }
}
