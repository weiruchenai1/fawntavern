package me.rerere.fawntavern.data.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ApiConfigStore {
    private const val PREFS = "api_config"
    private const val KEY_PROVIDERS = "providers"
    private const val KEY_CURRENT = "current_model"
    private const val KEY_PRESETS_VERSION = "presets_version"
    private const val PRESETS_VERSION = 5

    /** 已从预设中移除的提供商（迁移时若用户未配置密钥则一并删除） */
    private val retiredPresetNames = setOf("groq", "mistral")

    /** 预置的常见模型提供商（默认全部禁用、不带模型） */
    private fun defaultProviders(): List<ApiProvider> = listOf(
        ApiProvider(
            name = "OpenAI", type = "openai",
            baseUrl = "https://api.openai.com/v1", enabled = false,
        ),
        ApiProvider(
            name = "Gemini", type = "google",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta", enabled = false,
        ),
        ApiProvider(
            name = "Claude", type = "claude",
            baseUrl = "https://api.anthropic.com/v1", enabled = false,
        ),
        ApiProvider(
            name = "DeepSeek", type = "openai",
            baseUrl = "https://api.deepseek.com/v1", enabled = false,
            balanceEnabled = true,
            balancePath = "/user/balance",
            balanceJsonKey = "balance_infos[0].total_balance",
        ),
        ApiProvider(
            name = "OpenRouter", type = "openai",
            baseUrl = "https://openrouter.ai/api/v1", enabled = false,
        ),
        ApiProvider(
            name = "SiliconFlow", type = "openai",
            baseUrl = "https://api.siliconflow.cn/v1", enabled = false,
            balanceEnabled = true,
            balancePath = "/user/info",
            balanceJsonKey = "data.totalBalance",
        ),
        ApiProvider(
            name = "New API", type = "openai",
            baseUrl = "", enabled = false,
        ),
        ApiProvider(
            name = "Grok", type = "openai",
            baseUrl = "https://api.x.ai/v1", enabled = false,
        ),
        ApiProvider(
            name = "Qwen", type = "openai",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1", enabled = false,
        ),
        ApiProvider(
            name = "Doubao", type = "openai",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3", enabled = false,
        ),
        ApiProvider(
            name = "Kling", type = "openai",
            baseUrl = "", enabled = false,
        ),
        ApiProvider(
            name = "MoonshotAI", type = "openai",
            baseUrl = "https://api.moonshot.cn/v1", enabled = false,
        ),
        ApiProvider(
            name = "01.AI", type = "openai",
            baseUrl = "https://api.lingyiwanwu.com/v1", enabled = false,
        ),
    )

    fun loadConfig(context: Context): ApiConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getString(KEY_PROVIDERS, null)
        if (raw == null) {
            // 首次启动 —— 写入预设提供商作为初始配置
            val config = ApiConfig(providers = defaultProviders())
            saveConfig(context, config)
            p.edit().putInt(KEY_PRESETS_VERSION, PRESETS_VERSION).apply()
            return config
        }

        val providers = mutableListOf<ApiProvider>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val models = mutableListOf<String>()
                obj.optJSONArray("models")?.let { ma ->
                    for (j in 0 until ma.length()) models.add(ma.getString(j))
                }
                providers.add(ApiProvider(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    name = obj.optString("name", ""),
                    type = obj.optString("type", "openai"),
                    baseUrl = obj.optString("baseUrl", ""),
                    apiKey = obj.optString("apiKey", ""),
                    enabled = obj.optBoolean("enabled", true),
                    models = models,
                    balanceEnabled = obj.optBoolean("balanceEnabled", false),
                    balancePath = obj.optString("balancePath", ""),
                    balanceJsonKey = obj.optString("balanceJsonKey", ""),
                ))
            }
        } catch (_: Exception) {}

        // 一次性迁移：刷新未配置的预设提供商 + 补充新增预设（不覆盖用户已配置密钥的提供商）
        if (p.getInt(KEY_PRESETS_VERSION, 1) < PRESETS_VERSION) {
            val presetByName = defaultProviders().associateBy { it.name.lowercase() }
            val refreshed = providers.map { prov ->
                val preset = presetByName[prov.name.lowercase()] ?: return@map prov
                if (prov.apiKey.isBlank()) {
                    // 未填过密钥 → 整体刷新为最新预设（保留 id）
                    preset.copy(id = prov.id)
                } else {
                    // 已填密钥 → 仅补齐缺失的预设字段
                    prov.copy(
                        baseUrl = prov.baseUrl.ifBlank { preset.baseUrl },
                        balanceEnabled = if (prov.balancePath.isBlank()) preset.balanceEnabled else prov.balanceEnabled,
                        balancePath = prov.balancePath.ifBlank { preset.balancePath },
                        balanceJsonKey = prov.balanceJsonKey.ifBlank { preset.balanceJsonKey },
                    )
                }
            }.toMutableList()
            // 移除已下架的预设提供商（用户填过密钥的保留）
            refreshed.removeAll { it.name.lowercase() in retiredPresetNames && it.apiKey.isBlank() }
            val existingNames = refreshed.map { it.name.lowercase() }.toSet()
            refreshed.addAll(defaultProviders().filter { it.name.lowercase() !in existingNames })
            val merged = ApiConfig(providers = refreshed, currentModel = p.getString(KEY_CURRENT, "") ?: "")
            saveConfig(context, merged)
            p.edit().putInt(KEY_PRESETS_VERSION, PRESETS_VERSION).apply()
            return merged
        }

        return ApiConfig(
            providers = providers,
            currentModel = p.getString(KEY_CURRENT, "") ?: "",
        )
    }

    /** 重置为预设提供商（清除所有用户配置，重新补种默认预设） */
    fun resetToDefaults(context: Context): ApiConfig {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        return loadConfig(context)
    }

    fun saveConfig(context: Context, config: ApiConfig) {
        val arr = JSONArray()
        config.providers.forEach { p ->
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            obj.put("type", p.type)
            obj.put("baseUrl", p.baseUrl)
            obj.put("apiKey", p.apiKey)
            obj.put("enabled", p.enabled)
            obj.put("models", JSONArray(p.models))
            obj.put("balanceEnabled", p.balanceEnabled)
            obj.put("balancePath", p.balancePath)
            obj.put("balanceJsonKey", p.balanceJsonKey)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROVIDERS, arr.toString())
            .putString(KEY_CURRENT, config.currentModel)
            .apply()
    }
}
