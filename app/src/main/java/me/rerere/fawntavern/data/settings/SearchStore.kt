package me.rerere.fawntavern.data.settings

import android.content.Context
import me.rerere.fawntavern.data.security.SecurePreferences
import me.rerere.fawntavern.data.search.SearchServiceOptions
import me.rerere.fawntavern.data.search.newSearchId
import org.json.JSONArray
import org.json.JSONObject

/**
 * 联网搜索设置（沿用 *Store 惯例：SharedPreferences + org.json 手写序列化）。
 *
 * 存储结构：总开关、结果条数、已配置的提供商列表（有序，每项含实例 id + 类型 key + 各自配置）、
 * 选中下标（联网搜索实际使用的提供商）。
 */
object SearchStore {
    private const val PREFS = "web_search"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SERVICES = "services"
    private const val KEY_SELECTED = "selected"
    private const val KEY_RESULT_SIZE = "result_size"
    private const val KEY_CORRUPTED = "services_corrupted"

    /** 联网搜索总开关：开启后发送消息时用最近一条用户消息联网搜索并注入结果 */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getResultSize(context: Context): Int =
        prefs(context).getInt(KEY_RESULT_SIZE, 5)

    fun setResultSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_RESULT_SIZE, size.coerceIn(3, 10)).apply()
    }

    /** 已配置的提供商列表（有序）。 */
    fun getServices(context: Context): List<SearchServiceOptions> {
        val p = prefs(context)
        val stored = p.getString(KEY_SERVICES, null)
        val raw = SecurePreferences.getString(context, p, KEY_SERVICES, null)
        if (raw == null) {
            if (stored == null) return createDefaults(context)
            return recoverDefaults(context, p)
        }
        val list = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { readService(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            return recoverDefaults(context, p)
        }
        return list.ifEmpty { recoverDefaults(context, p) }
    }

    /** 整表保存提供商列表；顺带把越界的选中下标收敛回 0 */
    fun setServices(context: Context, services: List<SearchServiceOptions>) {
        val arr = JSONArray()
        services.forEach { arr.put(toJson(it)) }
        val p = prefs(context)
        SecurePreferences.putString(context, p, KEY_SERVICES, arr.toString())
        val sel = p.getInt(KEY_SELECTED, 0)
        if (sel >= services.size) p.edit().putInt(KEY_SELECTED, 0).apply()
    }

    fun addService(context: Context, options: SearchServiceOptions) {
        setServices(context, getServices(context) + options)
    }

    fun updateService(context: Context, id: String, updated: SearchServiceOptions) {
        setServices(context, getServices(context).map { if (it.id == id) updated else it })
    }

    /** 删除提供商；列表至少保留一个 */
    fun removeService(context: Context, id: String) {
        val list = getServices(context).filter { it.id != id }
        if (list.isNotEmpty()) setServices(context, list)
    }

    /** 当前选中下标（实际用于联网搜索的提供商） */
    fun getSelectedIndex(context: Context): Int {
        val count = getServices(context).size
        return prefs(context).getInt(KEY_SELECTED, 0).coerceIn(0, (count - 1).coerceAtLeast(0))
    }

    fun setSelectedIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_SELECTED, index.coerceAtLeast(0)).apply()
    }

    /** 当前选中提供商配置 */
    fun getSelected(context: Context): SearchServiceOptions =
        getServices(context).getOrElse(getSelectedIndex(context)) { SearchServiceOptions.ALL.first() }

    fun consumeCorruptionNotice(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_CORRUPTED, false)) return false
        p.edit().putBoolean(KEY_CORRUPTED, false).apply()
        return true
    }

    internal fun exportPortable(context: Context): String = JSONObject()
        .put("enabled", isEnabled(context))
        .put("resultSize", getResultSize(context))
        .put("selected", getSelectedIndex(context))
        .put("services", JSONArray().apply { getServices(context).forEach { put(toJson(it)) } })
        .toString()

    internal fun parsePortable(raw: String): PortableSearchConfig {
        val root = JSONObject(raw)
        val servicesArray = root.getJSONArray("services")
        val services = (0 until servicesArray.length()).map { readService(servicesArray.getJSONObject(it)) }
        require(services.isNotEmpty()) { "Search configuration contains no services" }
        return PortableSearchConfig(
            enabled = root.optBoolean("enabled", false),
            resultSize = root.optInt("resultSize", 5).coerceIn(3, 10),
            selected = root.optInt("selected", 0).coerceIn(0, services.lastIndex),
            services = services,
        )
    }

    internal fun importPortable(context: Context, config: PortableSearchConfig) {
        val services = JSONArray().apply { config.services.forEach { put(toJson(it)) } }
        val p = prefs(context)
        SecurePreferences.putStringSync(context, p, KEY_SERVICES, services.toString())
        check(
            p.edit()
                .putInt(KEY_SELECTED, config.selected)
                .putInt(KEY_RESULT_SIZE, config.resultSize)
                .putBoolean(KEY_ENABLED, config.enabled)
                .commit()
        ) { "Unable to persist search configuration" }
    }

    internal data class PortableSearchConfig(
        val enabled: Boolean,
        val resultSize: Int,
        val selected: Int,
        val services: List<SearchServiceOptions>,
    )

    private fun recoverDefaults(context: Context, p: android.content.SharedPreferences): List<SearchServiceOptions> {
        val defaults = createDefaults(context)
        p.edit().putBoolean(KEY_CORRUPTED, true).apply()
        return defaults
    }

    private fun createDefaults(context: Context): List<SearchServiceOptions> {
        val defaults = listOf(SearchServiceOptions.fromKey(SearchServiceOptions.ALL.first().key))
        setServices(context, defaults)
        return defaults
    }

    // ── 序列化 ──

    private fun toJson(o: SearchServiceOptions): JSONObject = JSONObject()
        .put("id", o.id)
        .put("key", o.key)
        .apply {
            when (o) {
                is SearchServiceOptions.BingLocalOptions -> {}
                is SearchServiceOptions.TavilyOptions -> put("apiKey", o.apiKey).put("depth", o.depth)
                is SearchServiceOptions.ExaOptions -> put("apiKey", o.apiKey)
                is SearchServiceOptions.ZhipuOptions -> put("apiKey", o.apiKey)
                is SearchServiceOptions.SearXNGOptions -> put("url", o.url).put("engines", o.engines)
                    .put("language", o.language).put("username", o.username).put("password", o.password)
                is SearchServiceOptions.BraveOptions -> put("apiKey", o.apiKey)
                is SearchServiceOptions.DuckDuckGoOptions -> put("region", o.region)
                is SearchServiceOptions.LinkUpOptions -> put("apiKey", o.apiKey)
                is SearchServiceOptions.MetasoOptions -> put("apiKey", o.apiKey)
                is SearchServiceOptions.OllamaOptions -> put("apiKey", o.apiKey)
                is SearchServiceOptions.JinaOptions -> put("apiKey", o.apiKey)
                is SearchServiceOptions.BochaOptions -> put("apiKey", o.apiKey)
                is SearchServiceOptions.PerplexityOptions -> put("apiKey", o.apiKey)
                is SearchServiceOptions.SerperOptions -> put("apiKey", o.apiKey).put("gl", o.gl)
                    .put("hl", o.hl).put("tbs", o.tbs).put("page", o.page)
                is SearchServiceOptions.QueritOptions -> put("apiKey", o.apiKey).put("sitesInclude", o.sitesInclude)
                    .put("sitesExclude", o.sitesExclude).put("timeRange", o.timeRange)
                    .put("countries", o.countries).put("languages", o.languages)
                is SearchServiceOptions.GrokOptions -> put("apiKey", o.apiKey).put("model", o.model)
                    .put("reasoningEffort", o.reasoningEffort).put("customUrl", o.customUrl)
                    .put("systemPrompt", o.systemPrompt)
            }
        }

    private fun readService(obj: JSONObject): SearchServiceOptions {
        val base = SearchServiceOptions.fromKey(obj.optString("key"), obj.optString("id").ifBlank { newSearchId() })
        return when (base) {
            is SearchServiceOptions.TavilyOptions -> base.copy(apiKey = obj.optString("apiKey"), depth = obj.optString("depth").ifEmpty { "advanced" })
            is SearchServiceOptions.ExaOptions -> base.copy(apiKey = obj.optString("apiKey"))
            is SearchServiceOptions.ZhipuOptions -> base.copy(apiKey = obj.optString("apiKey"))
            is SearchServiceOptions.SearXNGOptions -> base.copy(
                url = obj.optString("url"), engines = obj.optString("engines"),
                language = obj.optString("language"), username = obj.optString("username"),
                password = obj.optString("password"),
            )
            is SearchServiceOptions.BraveOptions -> base.copy(apiKey = obj.optString("apiKey"))
            is SearchServiceOptions.DuckDuckGoOptions -> base.copy(region = obj.optString("region").ifEmpty { "us-en" })
            is SearchServiceOptions.LinkUpOptions -> base.copy(apiKey = obj.optString("apiKey"))
            is SearchServiceOptions.MetasoOptions -> base.copy(apiKey = obj.optString("apiKey"))
            is SearchServiceOptions.OllamaOptions -> base.copy(apiKey = obj.optString("apiKey"))
            is SearchServiceOptions.JinaOptions -> base.copy(apiKey = obj.optString("apiKey"))
            is SearchServiceOptions.BochaOptions -> base.copy(apiKey = obj.optString("apiKey"))
            is SearchServiceOptions.PerplexityOptions -> base.copy(apiKey = obj.optString("apiKey"))
            is SearchServiceOptions.SerperOptions -> base.copy(
                apiKey = obj.optString("apiKey"), gl = obj.optString("gl"),
                hl = obj.optString("hl"), tbs = obj.optString("tbs"), page = obj.optInt("page", 1),
            )
            is SearchServiceOptions.QueritOptions -> base.copy(
                apiKey = obj.optString("apiKey"), sitesInclude = obj.optString("sitesInclude"),
                sitesExclude = obj.optString("sitesExclude"), timeRange = obj.optString("timeRange"),
                countries = obj.optString("countries"), languages = obj.optString("languages"),
            )
            is SearchServiceOptions.GrokOptions -> base.copy(
                apiKey = obj.optString("apiKey"), model = obj.optString("model", SearchServiceOptions.GrokOptions.defaultModel),
                reasoningEffort = obj.optString("reasoningEffort", SearchServiceOptions.GrokOptions.defaultReasoningEffort),
                customUrl = obj.optString("customUrl", SearchServiceOptions.GrokOptions.defaultUrl),
                systemPrompt = obj.optString("systemPrompt", SearchServiceOptions.GrokOptions.defaultSystemPrompt),
            )
            is SearchServiceOptions.BingLocalOptions -> base
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
