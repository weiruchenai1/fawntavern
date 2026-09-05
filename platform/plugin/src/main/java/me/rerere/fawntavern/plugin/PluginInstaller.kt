package me.rerere.fawntavern.plugin

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.platform.plugin.BuildConfig
import me.rerere.fawntavern.core.version.SemanticVersion

/**
 * 插件安装 / 更新 / 卸载。
 *
 * 落盘策略：先解压到中转目录、全部校验通过后才动真实目录，且**旧版本先改名备份再换入新版本**——
 * 任一步失败都能把旧版本原样搬回来。中转目录只有一个，故安装串行（[installLock]）。
 */
object PluginInstaller {

    private val installLock = Mutex()

    /** 从 GitHub 仓库地址安装/更新。[url] 支持 `https://github.com/o/r`、`o/r`、`.../tree/<branch>`。 */
    suspend fun installFromGitHub(
        context: Context,
        url: String,
        appVersion: String = BuildConfig.APP_VERSION,
    ): PluginRepository.InstalledPlugin {
        val resolved = PluginGitHubSource.resolve(url)
        // 先用远端 manifest 早失败：不兼容/格式错的插件不值得把整个 zip 拉下来
        val remote = PluginManifestParser.parse(resolved.manifestJson)
        requireCompatible(remote, appVersion)
        val slug = "${resolved.repo.owner}/${resolved.repo.repo}"
        return PluginGitHubSource.downloadZip(resolved.repo) { stream ->
            install(
                context = context,
                input = stream,
                source = PluginRepository.SOURCE_GITHUB,
                repo = slug,
                branch = resolved.repo.branch,
                appVersion = appVersion,
                expectedManifest = remote,
            )
        }
    }

    /** 从本地 zip 安装/更新（插件作者调试、离线导入）。[input] 由调用方打开，本函数负责读完。 */
    suspend fun installFromZip(
        context: Context,
        input: InputStream,
        appVersion: String = BuildConfig.APP_VERSION,
    ): PluginRepository.InstalledPlugin = install(
        context = context,
        input = input,
        source = PluginRepository.SOURCE_LOCAL,
        repo = "",
        branch = "",
        appVersion = appVersion,
        expectedManifest = null,
    )

    suspend fun uninstall(context: Context, pluginId: String): Boolean =
        PluginRepository.delete(context, pluginId)

    /** Recover the rename-based install transaction before scanning installed plugins. */
    suspend fun recoverInterruptedInstalls(context: Context) = installLock.withLock {
        withContext(Dispatchers.IO) {
            PluginRepository.stagingDir(context).deleteRecursively()
            PluginRepository.root(context).listFiles()
                ?.filter { it.isDirectory && it.name.startsWith(BACKUP_PREFIX) }
                ?.forEach { backup ->
                    val pluginId = backup.name.removePrefix(BACKUP_PREFIX)
                    if (!PluginManifestParser.isValidId(pluginId)) {
                        backup.deleteRecursively()
                        return@forEach
                    }
                    val target = PluginRepository.dirOf(context, pluginId)
                    if (target.exists()) {
                        backup.deleteRecursively()
                    } else if (!backup.renameTo(target)) {
                        throw IOException("无法恢复安装中断前的插件版本: $pluginId")
                    }
                }
        }
    }

    /**
     * 检查更新：只拉远端 manifest.json 比版本，不下载 zip。
     * 返回可更新的新版本号；已是最新/无更新源/拉取失败均返回 null。
     */
    suspend fun checkUpdate(
        context: Context,
        plugin: PluginRepository.InstalledPlugin,
    ): String? = withContext(Dispatchers.IO) {
        val slug = plugin.meta.repo.takeIf { it.isNotBlank() }
            ?: plugin.manifest.updateRepo
            ?: return@withContext null
        val owner = slug.substringBefore('/').takeIf { it.isNotBlank() } ?: return@withContext null
        val repo = slug.substringAfter('/', "").takeIf { it.isNotBlank() } ?: return@withContext null
        runCatching {
            val branch = plugin.meta.branch.takeIf { it.isNotBlank() }
                ?: PluginGitHubSource.resolveBranch(owner, repo)
            val json = PluginGitHubSource.fetchFile(owner, repo, branch, "manifest.json") ?: return@runCatching null
            val remote = PluginManifestParser.parse(json)
            if (remote.id != plugin.manifest.id || !remote.isCompatibleWith(BuildConfig.APP_VERSION)) {
                return@runCatching null
            }
            val local = plugin.manifest.parsedVersion
            val latest = remote.parsedVersion
            if (local != null && latest != null && latest > local) remote.version else null
        }.getOrNull()
    }

    // ── 私有 ──────────────────────────────────────────────

    private suspend fun install(
        context: Context,
        input: InputStream,
        source: String,
        repo: String,
        branch: String,
        appVersion: String,
        expectedManifest: PluginManifest?,
    ): PluginRepository.InstalledPlugin = installLock.withLock {
        withContext(Dispatchers.IO) {
            val staging = PluginRepository.stagingDir(context)
            staging.deleteRecursively()
            try {
                PluginArchive.extract(input, staging)
                val mirror = PluginArchive.resolveRoot(staging)
                val manifest = PluginManifestParser.parse(File(mirror, "manifest.json").readText())
                if (expectedManifest != null &&
                    (manifest.id != expectedManifest.id || manifest.version != expectedManifest.version)
                ) {
                    throw PluginFormatException("下载内容与预检 manifest 不一致，请重试")
                }
                requireCompatible(manifest, appVersion)
                val entry = File(mirror, manifest.entry)
                if (!entry.isFile) {
                    throw PluginFormatException("入口文件不存在: ${manifest.entry}")
                }
                if (entry.length() > MAX_ENTRY_SOURCE_BYTES) {
                    throw PluginFormatException("插件入口超过 384KB: ${manifest.entry}")
                }
                PluginRepository.writeMeta(
                    mirror,
                    PluginRepository.InstallMeta(
                        source = source,
                        repo = repo,
                        branch = branch,
                        version = manifest.version,
                        installedAt = System.currentTimeMillis(),
                    ),
                )
                val target = swapIn(context, manifest.id, mirror)
                PluginRepository.read(target)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    /** 旧版本改名备份 → 换入新版本 → 删备份；中途失败把旧版本搬回来。 */
    private fun swapIn(context: Context, pluginId: String, mirror: File): File {
        val target = PluginRepository.dirOf(context, pluginId)
        val backup = PluginRepository.backupDirOf(context, pluginId)
        backup.deleteRecursively()
        val hadOld = target.isDirectory
        if (hadOld && !target.renameTo(backup)) {
            throw IOException("无法替换旧版本（目录被占用）: $pluginId")
        }
        val moved = try {
            mirror.renameTo(target)
        } catch (e: Exception) {
            if (hadOld) backup.renameTo(target)
            throw e
        }
        if (!moved) {
            if (hadOld) backup.renameTo(target)
            throw IOException("无法写入插件目录: $pluginId")
        }
        backup.deleteRecursively()
        return target
    }

    private fun requireCompatible(manifest: PluginManifest, appVersion: String) {
        if (manifest.isCompatibleWith(appVersion)) return
        val range = buildString {
            manifest.minAppVersion.takeIf { it.isNotBlank() }?.let { append("最低 $it") }
            manifest.maxAppVersion.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append("，")
                append("最高 $it")
            }
        }
        throw PluginFormatException("插件要求的应用版本不满足（$range），当前 $appVersion")
    }

    /** 供 UI 展示「有新版本」用的比较，避免调用方各自解析 semver。 */
    fun isNewer(remoteVersion: String, localVersion: String): Boolean {
        val remote = SemanticVersion.parse(remoteVersion) ?: return false
        val local = SemanticVersion.parse(localVersion) ?: return false
        return remote > local
    }

    private const val MAX_ENTRY_SOURCE_BYTES = 384L * 1024
    private const val BACKUP_PREFIX = ".old-"
}
