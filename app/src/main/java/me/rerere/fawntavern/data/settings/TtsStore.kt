package me.rerere.fawntavern.data.settings

import android.content.Context
import me.rerere.fawntavern.data.speech.TTSProviderSetting
import me.rerere.fawntavern.data.speech.newTtsId
import org.json.JSONArray
import org.json.JSONObject

/**
 * TTS（语音服务）设置（沿用 *Store 惯例：SharedPreferences + org.json 手写序列化）。
 *
 * 存储结构：已配置的提供商列表（有序，每项含实例 id + 类型 key + 各自配置）与选中 id
 * （朗读实际使用的提供商）。旧版「单选中 + 扁平分字段」格式读到时自动迁移成单元素列表。
 */
object TtsStore {
    private const val PREFS = "tts"
    private const val KEY_SERVICES = "services"
    private const val KEY_SELECTED = "selected"

    /** 已配置的提供商列表（有序）。旧格式首次读到会迁移并写回。 */
    fun getServices(context: Context): List<TTSProviderSetting> {
        val raw = prefs(context).getString(KEY_SERVICES, null)
        if (raw == null) return migrateLegacy(context)
        val list = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { readService(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
        return list.ifEmpty { listOf(TTSProviderSetting.SystemTTS()) }
    }

    /** 整表保存提供商列表；选中 id 缺失或不在列表时收敛到第一个 */
    fun setServices(context: Context, services: List<TTSProviderSetting>) {
        val arr = JSONArray()
        services.forEach { arr.put(toJson(it)) }
        prefs(context).edit().putString(KEY_SERVICES, arr.toString()).apply()
        val selectedId = prefs(context).getString(KEY_SELECTED, null)
        if (selectedId == null || services.none { it.id == selectedId }) {
            prefs(context).edit().putString(KEY_SELECTED, services.firstOrNull()?.id ?: "").apply()
        }
    }

    fun addService(context: Context, options: TTSProviderSetting) {
        setServices(context, getServices(context) + options)
    }

    fun updateService(context: Context, id: String, updated: TTSProviderSetting) {
        setServices(context, getServices(context).map { if (it.id == id) updated else it })
    }

    /** 删除提供商；列表至少保留一个，删除选中项时把选中切到第一个 */
    fun removeService(context: Context, id: String) {
        val list = getServices(context).filter { it.id != id }
        if (list.isNotEmpty()) setServices(context, list)
    }

    fun getSelectedId(context: Context): String {
        val services = getServices(context)
        val id = prefs(context).getString(KEY_SELECTED, null)
        return if (id != null && services.any { it.id == id }) id else services.first().id
    }

    fun setSelectedId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_SELECTED, id).apply()
    }

    /** 当前选中提供商（朗读实际使用的配置），保留此名供 TtsEngine 读取 */
    fun getSetting(context: Context): TTSProviderSetting {
        val services = getServices(context)
        return services.find { it.id == getSelectedId(context) } ?: services.first()
    }

    // ── 序列化 ──

    private fun toJson(o: TTSProviderSetting): JSONObject = JSONObject()
        .put("id", o.id)
        .put("key", o.key)
        .put("name", o.name)
        .apply {
            when (o) {
                is TTSProviderSetting.SystemTTS -> put("speechRate", o.speechRate).put("pitch", o.pitch)
                is TTSProviderSetting.OpenAI -> put("apiKey", o.apiKey).put("baseUrl", o.baseUrl).put("model", o.model).put("voice", o.voice)
                is TTSProviderSetting.Groq -> put("apiKey", o.apiKey).put("baseUrl", o.baseUrl).put("model", o.model).put("voice", o.voice)
                is TTSProviderSetting.XAI -> put("apiKey", o.apiKey).put("baseUrl", o.baseUrl).put("voiceId", o.voiceId).put("language", o.language)
                is TTSProviderSetting.Gemini -> put("apiKey", o.apiKey).put("baseUrl", o.baseUrl).put("model", o.model).put("voiceName", o.voiceName)
                is TTSProviderSetting.MiniMax -> put("apiKey", o.apiKey).put("baseUrl", o.baseUrl).put("model", o.model).put("voiceId", o.voiceId).put("emotion", o.emotion).put("speed", o.speed)
                is TTSProviderSetting.Qwen -> put("apiKey", o.apiKey).put("baseUrl", o.baseUrl).put("model", o.model).put("voice", o.voice).put("languageType", o.languageType)
                is TTSProviderSetting.MiMo -> put("apiKey", o.apiKey).put("baseUrl", o.baseUrl).put("model", o.model).put("voice", o.voice)
            }
        }

    private fun readService(obj: JSONObject): TTSProviderSetting {
        val base = TTSProviderSetting.fromKey(obj.optString("key"), obj.optString("id").ifBlank { newTtsId() })
        val name = obj.optString("name", base.name)
        return when (base) {
            is TTSProviderSetting.SystemTTS -> base.copy(
                name = name,
                speechRate = obj.optDouble("speechRate", 1.0).toFloat(),
                pitch = obj.optDouble("pitch", 1.0).toFloat(),
            )
            is TTSProviderSetting.OpenAI -> base.copy(
                name = name, apiKey = obj.optString("apiKey"),
                baseUrl = obj.optString("baseUrl", base.baseUrl),
                model = obj.optString("model", base.model),
                voice = obj.optString("voice", base.voice),
            )
            is TTSProviderSetting.Groq -> base.copy(
                name = name, apiKey = obj.optString("apiKey"),
                baseUrl = obj.optString("baseUrl", base.baseUrl),
                model = obj.optString("model", base.model),
                voice = obj.optString("voice", base.voice),
            )
            is TTSProviderSetting.XAI -> base.copy(
                name = name, apiKey = obj.optString("apiKey"),
                baseUrl = obj.optString("baseUrl", base.baseUrl),
                voiceId = obj.optString("voiceId", base.voiceId),
                language = obj.optString("language", base.language),
            )
            is TTSProviderSetting.Gemini -> base.copy(
                name = name, apiKey = obj.optString("apiKey"),
                baseUrl = obj.optString("baseUrl", base.baseUrl),
                model = obj.optString("model", base.model),
                voiceName = obj.optString("voiceName", base.voiceName),
            )
            is TTSProviderSetting.MiniMax -> base.copy(
                name = name, apiKey = obj.optString("apiKey"),
                baseUrl = obj.optString("baseUrl", base.baseUrl),
                model = obj.optString("model", base.model),
                voiceId = obj.optString("voiceId", base.voiceId),
                emotion = obj.optString("emotion", base.emotion),
                speed = obj.optDouble("speed", 1.0).toFloat(),
            )
            is TTSProviderSetting.Qwen -> base.copy(
                name = name, apiKey = obj.optString("apiKey"),
                baseUrl = obj.optString("baseUrl", base.baseUrl),
                model = obj.optString("model", base.model),
                voice = obj.optString("voice", base.voice),
                languageType = obj.optString("languageType", base.languageType),
            )
            is TTSProviderSetting.MiMo -> base.copy(
                name = name, apiKey = obj.optString("apiKey"),
                baseUrl = obj.optString("baseUrl", base.baseUrl),
                model = obj.optString("model", base.model),
                voice = obj.optString("voice", base.voice),
            )
        }
    }

    /** 旧格式（单选中 provider + 扁平分字段）迁移成单元素列表并写回 */
    private fun migrateLegacy(context: Context): List<TTSProviderSetting> {
        val p = prefs(context)
        val legacyKey = p.getString("provider", null)
        val service = when (legacyKey) {
            "openai" -> TTSProviderSetting.OpenAI(
                apiKey = p.getString("openai_api_key", "") ?: "",
                baseUrl = p.getString("openai_base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
                model = p.getString("openai_model", "gpt-4o-mini-tts") ?: "gpt-4o-mini-tts",
                voice = p.getString("openai_voice", "alloy") ?: "alloy",
            )
            else -> TTSProviderSetting.SystemTTS(
                speechRate = p.getFloat("system_rate", 1.0f),
                pitch = p.getFloat("system_pitch", 1.0f),
            )
        }
        setServices(context, listOf(service))
        return listOf(service)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
