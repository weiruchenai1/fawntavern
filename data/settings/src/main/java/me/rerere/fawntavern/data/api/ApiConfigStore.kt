package me.rerere.fawntavern.data.api

import androidx.core.content.edit
import me.rerere.fawntavern.data.commitChanges

import android.content.Context
import me.rerere.fawntavern.data.security.SecurePreferences
import org.json.JSONArray
import org.json.JSONObject

object ApiConfigStore {
    private const val PREFS = "api_config"
    private const val KEY_PROVIDERS = "providers"
    private const val KEY_CURRENT = "current_model"
    private const val KEY_CORRUPTED = "providers_corrupted"
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val SCHEMA_VERSION = 6
    private const val BUILTIN_HF_Z_IMAGE_ID = "builtin-hf-z-image-community"
    private const val BUILTIN_HF_Z_IMAGE_OFFICIAL_ID = "builtin-hf-z-image-official"
    private const val HF_Z_IMAGE_URL = "https://mrfakename-z-image-turbo.hf.space"
    private const val HF_Z_IMAGE_OFFICIAL_URL = "https://tongyi-mai-z-image-turbo.hf.space"
    private const val HF_Z_IMAGE_MODEL_ID = "z-image-turbo"
    private const val HF_Z_IMAGE_OFFICIAL_MODEL_ID = "tongyi-mai/z-image-turbo"
    /** 预置的常见模型提供商；图片 Space 作为内置提供商随默认配置启用。 */
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
        ApiProvider(
            name = "阿里云百炼", type = "openai",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1", enabled = false,
        ),
        ApiProvider(
            name = "火山引擎", type = "openai",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3", enabled = false,
        ),
        ApiProvider(
            name = "智谱AI开放平台", type = "openai",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4", enabled = false,
        ),
        ApiProvider(
            id = BUILTIN_HF_Z_IMAGE_ID,
            name = "mrfakename Space", type = "gradio",
            baseUrl = HF_Z_IMAGE_URL, apiPath = "/generate_image",
            enabled = true,
            models = listOf(ModelInfo(
                id = HF_Z_IMAGE_MODEL_ID, displayName = "Z-Image Turbo",
                inputModalities = listOf(Modality.TEXT), outputModalities = listOf(Modality.IMAGE),
                type = ModelType.IMAGE,
            )),
        ),
        ApiProvider(
            id = BUILTIN_HF_Z_IMAGE_OFFICIAL_ID,
            name = "Tongyi-MAI Space", type = "gradio",
            baseUrl = HF_Z_IMAGE_OFFICIAL_URL, apiPath = "/generate",
            enabled = true,
            gradioImageProfile = GradioImageProfile.Z_IMAGE_OFFICIAL,
            models = listOf(ModelInfo(
                id = HF_Z_IMAGE_OFFICIAL_MODEL_ID, displayName = "Z-Image Turbo",
                inputModalities = listOf(Modality.TEXT), outputModalities = listOf(Modality.IMAGE),
                type = ModelType.IMAGE,
            )),
        ),
    )

    fun loadConfig(context: Context): ApiConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = p.getString(KEY_PROVIDERS, null)
        val raw = SecurePreferences.getString(context, p, KEY_PROVIDERS, null)
        if (raw == null) {
            if (stored != null) p.edit { putBoolean(KEY_CORRUPTED, true) }
            val config = ApiConfig(providers = defaultProviders())
            saveConfig(context, config)
            if (stored != null) p.edit { putBoolean(KEY_CORRUPTED, true) }
            return config
        }

        val providers = mutableListOf<ApiProvider>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val models = mutableListOf<ModelInfo>()
                obj.optJSONArray("models")?.let { ma ->
                    for (j in 0 until ma.length()) {
                        ma.optJSONObject(j)?.let { models.add(modelFromJson(it)) }
                    }
                }
                val type = obj.optString("type", "openai")
                val useResponseApi = obj.optBoolean("useResponseApi", false)
                val legacyApiPath = obj.optString("apiPath", "")
                val migratedModels = migrateChatRoutes(models, type = type, useResponseApi = useResponseApi)
                providers.add(ApiProvider(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    name = obj.optString("name", ""),
                    type = type,
                    baseUrl = obj.optString("baseUrl", ""),
                    apiPath = legacyApiPath,
                    chatApiPath = obj.pathOrLegacy(
                        "chatApiPath",
                        legacyApiPath.takeIf { type == "openai" && !useResponseApi }.orEmpty(),
                    ),
                    responsesApiPath = obj.pathOrLegacy(
                        "responsesApiPath",
                        legacyApiPath.takeIf { type == "openai" && useResponseApi }.orEmpty(),
                    ),
                    imageGenerationApiPath = obj.optString("imageGenerationApiPath", ""),
                    imageEditApiPath = obj.optString("imageEditApiPath", ""),
                    apiKey = obj.optString("apiKey", ""),
                    enabled = obj.optBoolean("enabled", true),
                    useResponseApi = useResponseApi,
                    models = migratedModels,
                    balanceEnabled = obj.optBoolean("balanceEnabled", false),
                    balancePath = obj.optString("balancePath", ""),
                    balanceJsonKey = obj.optString("balanceJsonKey", ""),
                    gradioImageProfile = GradioImageProfile.entries.find {
                        it.name == obj.optString("gradioImageProfile")
                    } ?: GradioImageProfile.Z_IMAGE_COMMUNITY,
                ))
            }
        } catch (_: Exception) {
            val recovered = ApiConfig(providers = defaultProviders())
            saveConfig(context, recovered)
            p.edit { putBoolean(KEY_CORRUPTED, true) }
            return recovered
        }

        var config = ApiConfig(
            providers = providers,
            currentModel = p.getString(KEY_CURRENT, "") ?: "",
        ).withValidCurrentModel()
        if (p.getInt(KEY_SCHEMA_VERSION, 1) < SCHEMA_VERSION) {
            config = config.copy(providers = migrateBuiltInImageProviders(config.providers))
            saveConfig(context, config)
        }
        return config
    }

    /**
     * 第 6 版配置把内置图片提供商改为默认启用。迁移时同时补齐缺失项，并把旧版本中
     * 已存在的同一内置提供商启用，确保升级后的行为与全新安装一致。
     */
    internal fun migrateBuiltInImageProviders(providers: List<ApiProvider>): List<ApiProvider> =
        addMissingBuiltInImageProviders(providers).map { provider ->
            if (provider.isBuiltInImageProvider()) provider.copy(enabled = true) else provider
        }

    private fun ApiProvider.isBuiltInImageProvider(): Boolean =
        id == BUILTIN_HF_Z_IMAGE_ID || id == BUILTIN_HF_Z_IMAGE_OFFICIAL_ID

    private fun addMissingBuiltInImageProviders(providers: List<ApiProvider>): List<ApiProvider> {
        val out = providers.toMutableList()
        val defaults = defaultProviders()
        if (out.none { it.type == "gradio" && (it.baseUrl == HF_Z_IMAGE_URL || it.models.any { m -> m.id == HF_Z_IMAGE_MODEL_ID }) }) {
            out += defaults.first { it.id == BUILTIN_HF_Z_IMAGE_ID }
        }
        if (out.none { it.type == "gradio" && (it.baseUrl == HF_Z_IMAGE_OFFICIAL_URL || it.models.any { m -> m.id == HF_Z_IMAGE_OFFICIAL_MODEL_ID }) }) {
            out += defaults.first { it.id == BUILTIN_HF_Z_IMAGE_OFFICIAL_ID }
        }
        return out
    }

    /** 重置为预设提供商（清除所有用户配置，重新补种默认预设） */
    fun resetToDefaults(context: Context): ApiConfig {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        return loadConfig(context)
    }

    fun saveConfig(context: Context, config: ApiConfig) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        SecurePreferences.putString(context, prefs, KEY_PROVIDERS, providersToJson(config.providers).toString())
        prefs.edit {
            putString(KEY_CURRENT, config.currentModel)
            .putBoolean(KEY_CORRUPTED, false)
            .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        }
    }

    /** 备份恢复事务使用同步写入，以便在返回前确认配置已持久化。 */
    fun saveConfigSync(context: Context, config: ApiConfig) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        SecurePreferences.putStringSync(context, prefs, KEY_PROVIDERS, providersToJson(config.providers).toString())
        check(
            prefs.commitChanges {
                putString(KEY_CURRENT, config.currentModel)
                .putBoolean(KEY_CORRUPTED, false)
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            }
        ) { "Unable to persist API configuration" }
    }

    /** 导出不包含存储加密包装的可移植配置。 */
    fun exportPortable(context: Context): String {
        val config = loadConfig(context)
        return JSONObject()
            .put("formatVersion", SCHEMA_VERSION)
            .put("providers", providersToJson(config.providers))
            .put("currentModel", config.currentModel)
            .toString()
    }

    /** 解析并校验备份中的可移植配置。 */
    fun parsePortable(raw: String): ApiConfig {
        val root = JSONObject(raw)
        require(root.optInt("formatVersion", 1) in 1..SCHEMA_VERSION) {
            "Unsupported API configuration version"
        }
        val providers = providersFromJson(root.getJSONArray("providers"))
        require(providers.isNotEmpty()) { "API configuration contains no providers" }
        return ApiConfig(
            providers = providers,
            currentModel = root.optString("currentModel"),
        ).withValidCurrentModel()
    }

    private fun providersToJson(providers: List<ApiProvider>): JSONArray = JSONArray().apply {
        providers.forEach { p ->
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            obj.put("type", p.type)
            obj.put("baseUrl", p.baseUrl)
            obj.put("apiPath", p.apiPath)
            obj.put("chatApiPath", p.chatApiPath)
            obj.put("responsesApiPath", p.responsesApiPath)
            obj.put("imageGenerationApiPath", p.imageGenerationApiPath)
            obj.put("imageEditApiPath", p.imageEditApiPath)
            obj.put("apiKey", p.apiKey)
            obj.put("enabled", p.enabled)
            obj.put("useResponseApi", p.useResponseApi)
            obj.put("models", JSONArray().apply { p.models.forEach { put(modelToJson(it)) } })
            obj.put("balanceEnabled", p.balanceEnabled)
            obj.put("balancePath", p.balancePath)
            obj.put("balanceJsonKey", p.balanceJsonKey)
            obj.put("gradioImageProfile", p.gradioImageProfile.name)
            put(obj)
        }
    }

    private fun providersFromJson(arr: JSONArray): List<ApiProvider> =
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val models = mutableListOf<ModelInfo>()
            obj.optJSONArray("models")?.let { modelArray ->
                for (j in 0 until modelArray.length()) {
                    modelArray.optJSONObject(j)?.let { models.add(modelFromJson(it)) }
                }
            }
            val type = obj.optString("type", "openai")
            val useResponseApi = obj.optBoolean("useResponseApi", false)
            val legacyApiPath = obj.optString("apiPath", "")
            val migratedModels = migrateChatRoutes(models, type = type, useResponseApi = useResponseApi)
            ApiProvider(
                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                name = obj.optString("name", ""),
                type = type,
                baseUrl = obj.optString("baseUrl", ""),
                apiPath = legacyApiPath,
                chatApiPath = obj.pathOrLegacy(
                    "chatApiPath",
                    legacyApiPath.takeIf { type == "openai" && !useResponseApi }.orEmpty(),
                ),
                responsesApiPath = obj.pathOrLegacy(
                    "responsesApiPath",
                    legacyApiPath.takeIf { type == "openai" && useResponseApi }.orEmpty(),
                ),
                imageGenerationApiPath = obj.optString("imageGenerationApiPath", ""),
                imageEditApiPath = obj.optString("imageEditApiPath", ""),
                apiKey = obj.optString("apiKey", ""),
                enabled = obj.optBoolean("enabled", true),
                useResponseApi = useResponseApi,
                models = migratedModels,
                balanceEnabled = obj.optBoolean("balanceEnabled", false),
                balancePath = obj.optString("balancePath", ""),
                balanceJsonKey = obj.optString("balanceJsonKey", ""),
                gradioImageProfile = GradioImageProfile.entries.find {
                    it.name == obj.optString("gradioImageProfile")
                } ?: GradioImageProfile.Z_IMAGE_COMMUNITY,
            )
        }

    fun consumeCorruptionNotice(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_CORRUPTED, false)) return false
        prefs.edit { putBoolean(KEY_CORRUPTED, false) }
        return true
    }

    // ── 模型元数据的 JSON 编解码 ──

    private fun modelToJson(m: ModelInfo): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("displayName", m.displayName)
        put("input", JSONArray(m.inputModalities.map { it.name }))
        put("output", JSONArray(m.outputModalities.map { it.name }))
        put("abilities", JSONArray(m.abilities.map { it.name }))
        put("modelType", m.type.name)
        put("imageGenerationRoute", m.imageGenerationRoute.name)
        put("chatGenerationRoute", m.chatGenerationRoute.name)
        put("tools", JSONArray(m.tools.map { it.name }))
        put("headers", kvToJson(m.headers))
        put("bodies", kvToJson(m.bodies))
    }

    private fun modelFromJson(obj: JSONObject): ModelInfo {
        val id = obj.optString("id")
        val output = obj.enums("output", Modality.entries).ifEmpty { listOf(Modality.TEXT) }
        return ModelInfo(
            id = id,
            displayName = obj.optString("displayName", id),
            inputModalities = obj.enums("input", Modality.entries).ifEmpty { listOf(Modality.TEXT) },
            outputModalities = output,
            abilities = obj.enums("abilities", ModelAbility.entries),
            tools = obj.enums("tools", BuiltInTool.entries).toSet(),
            headers = obj.kvList("headers"),
            bodies = obj.kvList("bodies"),
            type = ModelType.entries.find { it.name == obj.optString("modelType") }
                ?: modelTypeOf(output),
            imageGenerationRoute = ImageGenerationRoute.entries.find {
                it.name == obj.optString("imageGenerationRoute")
            } ?: ImageGenerationRoute.DIRECT,
            chatGenerationRoute = ChatGenerationRoute.entries.find {
                it.name == obj.optString("chatGenerationRoute")
            } ?: ChatGenerationRoute.PROVIDER_DEFAULT,
        )
    }

    private fun migrateChatRoutes(
        models: List<ModelInfo>,
        type: String,
        useResponseApi: Boolean,
    ): List<ModelInfo> {
        if (type != "openai") return models
        val route = if (useResponseApi) ChatGenerationRoute.RESPONSES
        else ChatGenerationRoute.CHAT_COMPLETIONS
        return models.map {
            if (it.type == ModelType.CHAT && it.chatGenerationRoute == ChatGenerationRoute.PROVIDER_DEFAULT) {
                it.copy(chatGenerationRoute = route)
            } else it
        }
    }

    private fun kvToJson(list: List<KeyValue>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("key", it.key).put("value", it.value)) }
    }

    /** 读枚举数组，认不出的名字跳过 */
    private fun <T : Enum<T>> JSONObject.enums(key: String, values: List<T>): List<T> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val name = arr.optString(i)
            values.find { it.name == name }
        }
    }

    private fun JSONObject.kvList(key: String): List<KeyValue> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            KeyValue(o.optString("key"), o.optString("value"))
        }
    }

    private fun JSONObject.pathOrLegacy(key: String, legacy: String): String =
        if (has(key)) optString(key, "") else legacy
}
