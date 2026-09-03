package me.rerere.fawntavern.data.settings

import androidx.core.content.edit
import me.rerere.fawntavern.data.commitChanges

import android.content.Context
import me.rerere.fawntavern.data.security.SecurePreferences
import me.rerere.fawntavern.data.speech.TTSProviderSetting
import me.rerere.fawntavern.data.speech.newTtsId
import org.json.JSONArray
import org.json.JSONObject

/**
 * TTS（语音服务）设置（沿用 *Store 惯例：SharedPreferences + org.json 手写序列化）。
 *
 * 存储结构：已配置的提供商列表（有序，每项含实例 id + 类型 key + 各自配置）与选中 id
 * （朗读实际使用的提供商）。
 */
object TtsStore {
    private const val PREFS = "tts"
    private const val KEY_SERVICES = "services"
    private const val KEY_SELECTED = "selected"
    private const val KEY_CORRUPTED = "services_corrupted"
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val SCHEMA_VERSION = 1

    /** 已配置的提供商列表（有序）。 */
    fun getServices(context: Context): List<TTSProviderSetting> {
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

    /** 整表保存提供商列表；选中 id 缺失或不在列表时收敛到第一个 */
    fun setServices(context: Context, services: List<TTSProviderSetting>) {
        val arr = JSONArray()
        services.forEach { arr.put(toJson(it)) }
        val p = prefs(context)
        SecurePreferences.putString(context, p, KEY_SERVICES, arr.toString())
        val selectedId = p.getString(KEY_SELECTED, null)
        p.edit {
            putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            if (selectedId == null || services.none { it.id == selectedId }) {
                putString(KEY_SELECTED, services.firstOrNull()?.id ?: "")
            }
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
        prefs(context).edit { putString(KEY_SELECTED, id) }
    }

    /** 当前选中提供商（朗读实际使用的配置），保留此名供 TtsEngine 读取 */
    fun getSetting(context: Context): TTSProviderSetting {
        val services = getServices(context)
        return services.find { it.id == getSelectedId(context) } ?: services.first()
    }

    fun consumeCorruptionNotice(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_CORRUPTED, false)) return false
        p.edit { putBoolean(KEY_CORRUPTED, false) }
        return true
    }

    fun exportPortable(context: Context): String = JSONObject()
        .put("formatVersion", SCHEMA_VERSION)
        .put("selectedId", getSelectedId(context))
        .put("services", JSONArray().apply { getServices(context).forEach { put(toJson(it)) } })
        .toString()

    fun parsePortable(raw: String): PortableTtsConfig {
        val root = JSONObject(raw)
        require(root.optInt("formatVersion", 1) in 1..SCHEMA_VERSION) {
            "Unsupported TTS configuration version"
        }
        val servicesArray = root.getJSONArray("services")
        val services = (0 until servicesArray.length()).map { readService(servicesArray.getJSONObject(it)) }
        require(services.isNotEmpty()) { "TTS configuration contains no services" }
        val selected = root.optString("selectedId").takeIf { id -> services.any { it.id == id } }
            ?: services.first().id
        return PortableTtsConfig(selected, services)
    }

    fun importPortable(context: Context, config: PortableTtsConfig) {
        val services = JSONArray().apply { config.services.forEach { put(toJson(it)) } }
        val p = prefs(context)
        SecurePreferences.putStringSync(context, p, KEY_SERVICES, services.toString())
        check(p.commitChanges {
            putString(KEY_SELECTED, config.selectedId)
            putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        }) {
            "Unable to persist TTS configuration"
        }
    }

    data class PortableTtsConfig(
        val selectedId: String,
        val services: List<TTSProviderSetting>,
    )

    private fun recoverDefaults(context: Context, p: android.content.SharedPreferences): List<TTSProviderSetting> {
        val defaults = createDefaults(context)
        p.edit { putBoolean(KEY_CORRUPTED, true) }
        return defaults
    }

    private fun createDefaults(context: Context): List<TTSProviderSetting> {
        val defaults = listOf(TTSProviderSetting.SystemTTS())
        setServices(context, defaults)
        return defaults
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

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
