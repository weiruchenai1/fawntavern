package me.rerere.fawntavern.plugin

import org.json.JSONObject

sealed interface PluginConfigField {
    val key: String
    val label: String

    data class BooleanField(
        override val key: String,
        override val label: String,
        val default: Boolean,
    ) : PluginConfigField

    data class IntegerField(
        override val key: String,
        override val label: String,
        val default: Int,
        val minimum: Int?,
        val maximum: Int?,
    ) : PluginConfigField

    data class StringField(
        override val key: String,
        override val label: String,
        val default: String,
        val options: List<String>,
    ) : PluginConfigField
}

/** Small, deterministic JSON-schema subset used by the native plugin settings form. */
object PluginConfigSchema {
    fun parse(raw: String?): List<PluginConfigField> {
        if (raw.isNullOrBlank()) return emptyList()
        val properties = runCatching { JSONObject(raw).optJSONObject("properties") }.getOrNull()
            ?: return emptyList()
        return properties.keys().asSequence().take(MAX_FIELDS).mapNotNull { key ->
            val schema = properties.optJSONObject(key) ?: return@mapNotNull null
            if (key.isBlank() || key.length > MAX_KEY_CHARS) return@mapNotNull null
            val safeKey = key
            val label = schema.optString("title", safeKey).ifBlank { safeKey }.take(MAX_LABEL_CHARS)
            when (schema.optString("type")) {
                "boolean" -> PluginConfigField.BooleanField(
                    key = safeKey,
                    label = label,
                    default = schema.optBoolean("default", false),
                )
                "integer" -> {
                    val minimum = schema.optIntOrNull("minimum")
                    val maximum = schema.optIntOrNull("maximum")
                    if (minimum != null && maximum != null && minimum > maximum) return@mapNotNull null
                    PluginConfigField.IntegerField(
                        key = safeKey,
                        label = label,
                        default = schema.optInt("default", minimum ?: 0).coerceTo(minimum, maximum),
                        minimum = minimum,
                        maximum = maximum,
                    )
                }
                "string" -> {
                    val options = schema.optJSONArray("enum")?.let { array ->
                        (0 until minOf(array.length(), MAX_OPTIONS))
                            .map { array.optString(it) }
                            .filter { it.isNotBlank() && it.length <= MAX_VALUE_CHARS }
                            .distinct()
                    }.orEmpty()
                    PluginConfigField.StringField(
                        key = safeKey,
                        label = label,
                        default = schema.optString("default").take(MAX_VALUE_CHARS),
                        options = options,
                    )
                }
                else -> null
            }
        }.toList()
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun Int.coerceTo(minimum: Int?, maximum: Int?): Int {
        var value = this
        if (minimum != null) value = value.coerceAtLeast(minimum)
        if (maximum != null) value = value.coerceAtMost(maximum)
        return value
    }

    private const val MAX_FIELDS = 32
    private const val MAX_OPTIONS = 50
    private const val MAX_KEY_CHARS = 80
    private const val MAX_LABEL_CHARS = 120
    private const val MAX_VALUE_CHARS = 256
}
