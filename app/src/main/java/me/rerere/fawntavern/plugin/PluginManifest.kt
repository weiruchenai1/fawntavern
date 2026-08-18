package me.rerere.fawntavern.plugin

import me.rerere.fawntavern.data.update.SemanticVersion
import org.json.JSONObject

/** 已接线的能力白名单（与 `extension/` 的能力接口一一对应）。 */
object PluginCapabilities {
    const val PROMPT_CONTRIBUTOR = "prompt-contributor"
    const val GENERATION_LIFECYCLE = "generation-lifecycle"
    val KNOWN = setOf(PROMPT_CONTRIBUTOR, GENERATION_LIFECYCLE)
}

/** 反域名 id：`dev.xxx.myplugin`。同时是插件目录名，故不能含路径分隔符。 */
private val REVERSE_DNS = Regex("^[a-zA-Z][a-zA-Z0-9_]*\\.[a-zA-Z0-9_.-]+$")

/** manifest 格式错误（id 非法 / 缺字段 / 版本非法 / 能力未知），消息给 UI 展示。 */
class PluginFormatException(message: String) : Exception(message)

/** 统一插件 manifest。[raw] 保留原文供详情页展示与后续字段扩展。 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val capabilities: List<String>,
    val permissions: List<String> = emptyList(),
    /** 入口 JS 相对镜像根的路径（如 `index.js`、`js/plugin.js`），已校验不越界。 */
    val entry: String,
    val icon: String = "",
    /** configSchema 原文 JSON 串；null = 无配置项。 */
    val configSchema: String? = null,
    val minAppVersion: String = "",
    val maxAppVersion: String = "",
    /** true 时在检查更新里静默检测。 */
    val autoUpdate: Boolean = true,
    /** 更新源 `owner/repo`；null = 无更新源（不可检查更新）。 */
    val updateRepo: String? = null,
    val homePage: String = "",
    val raw: JSONObject = JSONObject(),
) {
    /** 版本可比较；null 表示版本串非法（parse 已拦，正常不会出现）。 */
    val parsedVersion: SemanticVersion? get() = SemanticVersion.parse(version)

    /** 是否兼容本机 app 版本。 */
    fun isCompatibleWith(appVersion: String): Boolean {
        val app = SemanticVersion.parse(appVersion) ?: return false
        minAppVersion.takeIf { it.isNotBlank() }?.let { min ->
            val v = SemanticVersion.parse(min) ?: throw PluginFormatException("minAppVersion 非法: $min")
            if (app < v) return false
        }
        maxAppVersion.takeIf { it.isNotBlank() }?.let { max ->
            val v = SemanticVersion.parse(max) ?: throw PluginFormatException("maxAppVersion 非法: $max")
            if (app > v) return false
        }
        return true
    }
}

object PluginManifestParser {

    /** id 同时用作插件目录名，落盘前必须过这一关。 */
    fun isValidId(id: String): Boolean = REVERSE_DNS.matches(id)

    fun parse(json: String): PluginManifest {
        val obj = try {
            JSONObject(json)
        } catch (_: Exception) {
            throw PluginFormatException("manifest.json 不是合法 JSON")
        }
        val id = obj.optString("id").takeIf { it.isNotBlank() }
            ?: throw PluginFormatException("缺少 id")
        if (!REVERSE_DNS.matches(id)) {
            throw PluginFormatException("id 非法（应为反域名格式，如 dev.xxx.myplugin）: $id")
        }
        if (id.startsWith("builtin.")) {
            throw PluginFormatException("id 保留给内置扩展: $id")
        }
        val version = obj.optString("version").takeIf { it.isNotBlank() }
            ?: throw PluginFormatException("缺少 version")
        if (SemanticVersion.parse(version) == null) throw PluginFormatException("version 非法: $version")

        val capabilities = obj.optJSONArray("capabilities")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter(String::isNotBlank) }
            ?: emptyList()
        if (capabilities.isEmpty()) throw PluginFormatException("capabilities 为空，插件不会被任何能力接口消费")
        val unknown = capabilities.filter { it !in PluginCapabilities.KNOWN }
        if (unknown.isNotEmpty()) throw PluginFormatException("包含未知能力: ${unknown.joinToString()}")

        val permissions = obj.optJSONArray("permissions")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter(String::isNotBlank).distinct() }
            ?: emptyList()
        if (permissions.isNotEmpty()) {
            throw PluginFormatException("当前版本尚未开放插件权限: ${permissions.joinToString()}")
        }

        val entry = normalizeEntry(obj.optString("entry").ifBlank { "index.js" })

        return PluginManifest(
            id = id,
            name = obj.optString("name").ifBlank { id },
            version = version,
            author = obj.optString("author"),
            description = obj.optString("description"),
            capabilities = capabilities.distinct(),
            permissions = permissions,
            entry = entry,
            icon = obj.optString("icon"),
            configSchema = obj.optJSONObject("configSchema")?.toString(),
            minAppVersion = obj.optString("minAppVersion"),
            maxAppVersion = obj.optString("maxAppVersion"),
            autoUpdate = obj.optBoolean("autoUpdate", true),
            updateRepo = obj.optJSONObject("update")?.optString("repo")?.takeIf { it.isNotBlank() },
            homePage = obj.optString("homePage"),
            raw = obj,
        )
    }

    /** 入口路径归一为镜像内相对路径；越界/绝对路径直接判错，别留到装载期拼出个越狱路径。 */
    private fun normalizeEntry(raw: String): String {
        val v = raw.replace('\\', '/').removePrefix("./").trim()
        if (v.isBlank() || v.startsWith('/') || v.contains(':')) {
            throw PluginFormatException("entry 必须是镜像内的相对路径: $raw")
        }
        if (v.split('/').any { it == ".." || it.isBlank() }) {
            throw PluginFormatException("entry 越界: $raw")
        }
        if (!v.endsWith(".js")) throw PluginFormatException("entry 必须是 .js 文件: $raw")
        return v
    }

    /** 从 GitHub 仓库 URL 提取 `owner/repo`。 */
    fun extractRepoSlug(url: String): String? {
        val u = url.trim().substringBefore('?').substringBefore('#').removeSuffix("/").removeSuffix(".git")
        val idx = u.indexOf("github.com/")
        val body = if (idx >= 0) u.substring(idx + "github.com/".length) else return null
        val segs = body.split('/').filter(String::isNotBlank)
        if (segs.size < 2) return null
        return "${segs[0]}/${segs[1]}"
    }
}
