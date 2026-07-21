package me.rerere.stapp.extension

import android.content.Context
import org.json.JSONObject

/**
 * 扩展的持久化设置：启用状态 + 每扩展私有配置（JSON 串，由扩展自行编解码）。
 * 采用 org.json 手写序列化进 SharedPreferences（对齐 `data/api/ApiConfigStore` 的范式，
 * 因扩展配置是变长/异构的，不适合 `WorldInfoSettingsStore` 那种每字段扁平 key）。
 */
object ExtensionStore {
    private const val PREFS = "extensions"
    private const val K_ENABLED = "enabled"        // JSONObject: extId -> Boolean
    private const val K_CONFIG_PREFIX = "cfg_"     // 每扩展一条 config JSON 串

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 扩展是否启用；无记录时用 [default]（内置扩展默认启用）。 */
    fun isEnabled(ctx: Context, id: String, default: Boolean = true): Boolean {
        val raw = prefs(ctx).getString(K_ENABLED, null) ?: return default
        return try {
            val o = JSONObject(raw)
            if (o.has(id)) o.getBoolean(id) else default
        } catch (_: Exception) {
            default
        }
    }

    fun setEnabled(ctx: Context, id: String, enabled: Boolean) {
        val p = prefs(ctx)
        val o = try {
            JSONObject(p.getString(K_ENABLED, null) ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        o.put(id, enabled)
        p.edit().putString(K_ENABLED, o.toString()).apply()
    }

    /** 本扩展的配置 JSON 串（空 = 未配置，由扩展按自身默认处理）。 */
    fun getConfig(ctx: Context, id: String): String = prefs(ctx).getString(K_CONFIG_PREFIX + id, "") ?: ""

    fun setConfig(ctx: Context, id: String, json: String) {
        prefs(ctx).edit().putString(K_CONFIG_PREFIX + id, json).apply()
    }

    /** 已启用的扩展（注册表 ∩ 启用状态）。消费方据此再 `filterIsInstance<能力接口>()`。 */
    fun enabledExtensions(ctx: Context): List<Extension> =
        ExtensionHost.all().filter { isEnabled(ctx, it.info.id) }
}
