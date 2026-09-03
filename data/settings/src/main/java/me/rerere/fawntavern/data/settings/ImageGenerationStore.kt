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
            aspectRatio = item.optString("aspectRatio", "auto").takeIf { it in ImageGenerationSettings.ASPECT_RATIOS } ?: "auto",
            resolution = item.optString("resolution", "1k").takeIf { it in ImageGenerationSettings.RESOLUTIONS } ?: "1k",
            quality = item.optString("quality", "auto").takeIf { it in ImageGenerationSettings.QUALITIES } ?: "auto",
            steps = item.optInt("steps", ImageGenerationSettings.DEFAULT_STEPS)
                .coerceIn(ImageGenerationSettings.MIN_STEPS, ImageGenerationSettings.MAX_STEPS),
            seed = item.optInt("seed", -1).takeIf { it >= 0 },
            includeContext = item.optBoolean("includeContext", true),
        )
    }

    fun set(context: Context, modelKey: String, settings: ImageGenerationSettings) {
        if (modelKey.isBlank()) return
        val clean = settings.copy(
            count = settings.count.coerceIn(1, 5),
            aspectRatio = settings.aspectRatio.takeIf { it in ImageGenerationSettings.ASPECT_RATIOS } ?: "auto",
            resolution = settings.resolution.takeIf { it in ImageGenerationSettings.RESOLUTIONS } ?: "1k",
            quality = settings.quality.takeIf { it in ImageGenerationSettings.QUALITIES } ?: "auto",
            steps = settings.steps.coerceIn(ImageGenerationSettings.MIN_STEPS, ImageGenerationSettings.MAX_STEPS),
            seed = settings.seed?.takeIf { it >= 0 },
        )
        val root = read(context)
        root.put(modelKey, JSONObject()
            .put("count", clean.count)
            .put("aspectRatio", clean.aspectRatio)
            .put("resolution", clean.resolution)
            .put("quality", clean.quality)
            .put("steps", clean.steps)
            .put("seed", clean.seed ?: JSONObject.NULL)
            .put("includeContext", clean.includeContext))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_SETTINGS, root.toString()) }
    }

    private fun read(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SETTINGS, null)
        return runCatching { if (raw == null) JSONObject() else JSONObject(raw) }.getOrDefault(JSONObject())
    }

}
