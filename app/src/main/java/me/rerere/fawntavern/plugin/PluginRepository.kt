package me.rerere.fawntavern.plugin

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * `filesDir/plugins/` 的目录管理。只负责「装在哪、从哪来、什么版本」——
 * 启用状态与配置复用 [me.rerere.fawntavern.extension.ExtensionStore]（extId = 插件 id），不在这里另存一份。
 */
object PluginRepository {

    private const val DIR = "plugins"
    private const val META_FILE = ".install.json"

    const val SOURCE_GITHUB = "github"
    const val SOURCE_LOCAL = "local"

    /** 宿主侧安装元数据（非仓库内容）。 */
    data class InstallMeta(
        val source: String,
        /** `owner/repo`；本地导入为空。 */
        val repo: String = "",
        val branch: String = "",
        val version: String = "",
        val installedAt: Long = 0L,
    )

    data class InstalledPlugin(
        val manifest: PluginManifest,
        val meta: InstallMeta,
        val dir: File,
    )

    fun root(context: Context): File =
        File(context.filesDir.canonicalFile, DIR).also { it.mkdirs() }

    /** 插件目录。id 非法或规范化后越界即抛 —— id 来自不可信 manifest。 */
    fun dirOf(context: Context, pluginId: String): File {
        require(PluginManifestParser.isValidId(pluginId)) { "Invalid plugin id: $pluginId" }
        val parent = root(context).canonicalFile
        val target = File(parent, pluginId).canonicalFile
        require(target.parentFile == parent) { "Plugin directory escapes plugins root" }
        return target
    }

    /** 安装中转目录。以 `.` 开头，[list] 会跳过。 */
    fun stagingDir(context: Context): File = File(root(context), ".tmp-install")

    /** 覆盖安装时旧版本的暂存目录（安装失败要能原样搬回来）。 */
    fun backupDirOf(context: Context, pluginId: String): File =
        File(root(context), ".old-$pluginId")

    /** 扫盘装载。manifest 坏掉的目录跳过，不让一个坏插件挡住其余插件。 */
    suspend fun list(context: Context): List<InstalledPlugin> = withContext(Dispatchers.IO) {
        root(context).listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.mapNotNull { dir -> runCatching { read(dir) }.getOrNull() }
            ?.sortedBy { it.manifest.name.lowercase() }
            .orEmpty()
    }

    suspend fun get(context: Context, pluginId: String): InstalledPlugin? = withContext(Dispatchers.IO) {
        val dir = runCatching { dirOf(context, pluginId) }.getOrNull() ?: return@withContext null
        if (!dir.isDirectory) return@withContext null
        runCatching { read(dir) }.getOrNull()
    }

    /** 读一个插件目录：manifest.json 必须可解析，install.json 缺失则按本地导入兜底。 */
    fun read(dir: File): InstalledPlugin {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) throw PluginFormatException("插件目录缺少 manifest.json: ${dir.name}")
        val manifest = PluginManifestParser.parse(manifestFile.readText())
        if (!dir.name.startsWith('.') && dir.name != manifest.id) {
            throw PluginFormatException("插件目录名与 manifest id 不一致: ${dir.name} / ${manifest.id}")
        }
        return InstalledPlugin(manifest, readMeta(dir) ?: InstallMeta(SOURCE_LOCAL), dir)
    }

    fun readMeta(dir: File): InstallMeta? {
        val file = File(dir, META_FILE)
        if (!file.isFile) return null
        return runCatching {
            val o = JSONObject(file.readText())
            InstallMeta(
                source = o.optString("source", SOURCE_LOCAL),
                repo = o.optString("repo"),
                branch = o.optString("branch"),
                version = o.optString("version"),
                installedAt = o.optLong("installedAt"),
            )
        }.getOrNull()
    }

    fun writeMeta(dir: File, meta: InstallMeta) {
        val json = JSONObject()
            .put("source", meta.source)
            .put("repo", meta.repo)
            .put("branch", meta.branch)
            .put("version", meta.version)
            .put("installedAt", meta.installedAt)
        File(dir, META_FILE).writeText(json.toString())
    }

    suspend fun delete(context: Context, pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val dir = runCatching { dirOf(context, pluginId) }.getOrNull() ?: return@withContext false
        dir.isDirectory && dir.deleteRecursively()
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        root(context).listFiles()?.forEach { it.deleteRecursively() }
        Unit
    }
}
