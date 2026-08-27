package me.rerere.fawntavern.data.settings

import android.content.Context
import androidx.core.content.edit
import me.rerere.fawntavern.data.api.ImageGenerationSettings
import org.json.JSONObject

/** 图片生成控制项按模型记忆，避免切换模型后保留不兼容的参数。 */
object ImageGenerationStore {
    private const val PREFS = "image_generation"
    private const val KEY_SETTINGS = "settings"

    fun get(context: Context, modelKey: String): ImageGenerationSettings {
        if (modelKey.isBlank()) return ImageGenerationSettings()
        val item = read(context).optJSONObject(modelKey) ?: return ImageGenerationSettings()
        return ImageGenerationSettings(
            count = item.optInt("count", 1).coerceIn(1, 5),
            aspectRatio = item.optString("aspectRatio", "2:3").takeIf { it in ASPECT_RATIOS } ?: "2:3",
            resolution = item.optString("resolution", "1k").takeIf { it in RESOLUTIONS } ?: "1k",
            quality = item.optString("quality", "auto").takeIf { it in QUALITIES } ?: "auto",
        )
    }

    fun set(context: Context, modelKey: String, settings: ImageGenerationSettings) {
        if (modelKey.isBlank()) return
        val clean = settings.copy(
            count = settings.count.coerceIn(1, 5),
            aspectRatio = settings.aspectRatio.takeIf { it in ASPECT_RATIOS } ?: "2:3",
            resolution = settings.resolution.takeIf { it in RESOLUTIONS } ?: "1k",
            quality = settings.quality.takeIf { it in QUALITIES } ?: "auto",
        )
        val root = read(context)
        root.put(modelKey, JSONObject()
            .put("count", clean.count)
            .put("aspectRatio", clean.aspectRatio)
            .put("resolution", clean.resolution)
            .put("quality", clean.quality))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_SETTINGS, root.toString()) }
    }

    private fun read(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SETTINGS, null)
        return runCatching { if (raw == null) JSONObject() else JSONObject(raw) }.getOrDefault(JSONObject())
    }

    val ASPECT_RATIOS = listOf(
        "auto", "2:3", "3:2", "1:1", "9:16", "16:9", "4:3", "3:4", "2:1", "1:2",
        "4:5", "5:4", "21:9", "19.5:9", "9:19.5", "20:9", "9:20",
    )
    val RESOLUTIONS = listOf("1k", "2k", "4k")
    val QUALITIES = listOf("auto", "low", "medium", "high")
}
