package me.rerere.fawntavern.plugin

import java.io.IOException
import java.io.InputStream
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.api.Http
import okhttp3.Request
import org.json.JSONObject

/**
 * 从 GitHub 仓库安装插件的拉取层。
 *
 * - `raw.githubusercontent.com` 拉文件没有 API 限流；`api.github.com` 有（未带 token 时很容易打满），
 *   所以分支解析按「API 默认分支 → raw 探测 main/master → 仓库 HTML 里的 defaultBranch」降级。
 * - 仓库即插件：根目录包含 `manifest.json` 和已经打包好的单一 JS 入口，不加载运行时相对模块。
 */
object PluginGitHubSource {

    data class Repo(val owner: String, val repo: String, val branch: String)

    data class Resolved(
        val repo: Repo,
        /** 仓库根目录 manifest.json 原文。 */
        val manifestJson: String,
    )

    /**
     * 解析仓库 URL → (owner, repo, 显式分支?)。支持：
     * `https://github.com/o/r`、`o/r`、`.../tree/<branch>`、`.../o/r.git`、`git@github.com:o/r.git`。
     */
    fun parseUrl(url: String): Triple<String, String, String?>? {
        var u = url.trim().substringBefore('?').substringBefore('#')
        if (u.isEmpty()) return null
        if (u.startsWith("git@")) u = u.removePrefix("git@").replaceFirst(':', '/')
        u = when {
            u.startsWith("https://") -> {
                val parsed = runCatching { URI(u) }.getOrNull() ?: return null
                val host = parsed.host.orEmpty()
                if (!host.equals("github.com", ignoreCase = true) &&
                    !host.equals("www.github.com", ignoreCase = true)
                ) return null
                parsed.path.trimStart('/')
            }
            u.startsWith("github.com/", ignoreCase = true) -> u.substringAfter('/')
            else -> {
                if (u.contains("://") || u.contains('@')) return null
                u
            }
        }
        u = u.removeSuffix(".git").removeSuffix("/")
        val segs = u.split('/').filter(String::isNotBlank)
        if (segs.size < 2) return null
        val owner = segs[0]
        val repo = segs[1]
        if (!GITHUB_SEGMENT.matches(owner) || !GITHUB_SEGMENT.matches(repo)) return null
        val branch = when {
            segs.size >= 4 && segs[2] == "tree" -> segs[3].takeIf(String::isNotBlank)
            else -> null
        }
        return Triple(owner, repo, branch)
    }

    /** 解析 URL + 探测分支，拉根目录 manifest.json。 */
    suspend fun resolve(repoUrl: String): Resolved {
        val (owner, repo, explicit) = parseUrl(repoUrl)
            ?: throw IOException("无法解析 GitHub 仓库地址: $repoUrl")
        val branch = explicit ?: resolveBranch(owner, repo)
        val json = fetchFile(owner, repo, branch, "manifest.json")
            ?: throw IOException("仓库 $owner/$repo 根目录没有 manifest.json（分支 $branch）")
        return Resolved(Repo(owner, repo, branch), json)
    }

    /** 探测默认分支：API default_branch → raw 探测 main/master → 仓库 HTML defaultBranch。 */
    suspend fun resolveBranch(owner: String, repo: String): String {
        apiDefaultBranch(owner, repo)?.let { return it }
        for (candidate in listOf("main", "master")) {
            val (code, _) = rawGet(rawUrl(owner, repo, candidate, "manifest.json"))
            if (code == 200) return candidate
        }
        val (code, html) = rawGet("https://github.com/$owner/$repo")
        if (code == 200) parseDefaultBranchFromHtml(html)?.let { return it }
        throw IOException("无法解析仓库 $owner/$repo 的默认分支（API 限流且 main/master 都不存在）")
    }

    /** 拉仓库内某文件；404 → null，其余错误抛异常。 */
    suspend fun fetchFile(owner: String, repo: String, branch: String, path: String): String? {
        val (code, body) = rawGet(rawUrl(owner, repo, branch, path))
        return when (code) {
            200 -> body
            404 -> null
            else -> throw IOException("拉取 $owner/$repo/$path 失败: HTTP $code")
        }
    }

    /**
     * 整仓 zip 直下。走 codeload 而非 API `git/trees`：一次请求拿全树、不受 API 限流，
     * 且与本地 zip 导入共用同一条解压落盘代码。流在 [block] 返回后关闭，别把它带出去。
     */
    suspend fun <T> downloadZip(repo: Repo, block: suspend (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val url = "https://codeload.github.com/${repo.owner}/${repo.repo}/zip/refs/heads/${repo.branch}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "FawnTavern-Android")
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载 ${repo.owner}/${repo.repo} (${repo.branch}) 失败: HTTP ${response.code}")
            }
            block(response.body.byteStream())
        }
    }

    // ── 私有 ──────────────────────────────────────────────

    private fun rawUrl(owner: String, repo: String, branch: String, path: String) =
        "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"

    private suspend fun rawGet(url: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "FawnTavern-Android")
            .build()
        Http.client.newCall(request).execute().use { response ->
            response.code to runCatching { response.body.string() }.getOrDefault("")
        }
    }

    private suspend fun apiDefaultBranch(owner: String, repo: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "FawnTavern-Android")
                .build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else JSONObject(response.body.string()).optString("default_branch").takeIf(String::isNotBlank)
            }
        }.getOrNull()
    }

    /** 仓库 HTML 里的 `"defaultBranch":"main"`（已验证该标记存在，作为无 API/探测失败的兜底）。 */
    private fun parseDefaultBranchFromHtml(html: String): String? {
        val marker = "\"defaultBranch\":\""
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val start = idx + marker.length
        val end = html.indexOf('"', start)
        if (end < 0 || end == start) return null
        return html.substring(start, end)
    }

    private val GITHUB_SEGMENT = Regex("^[A-Za-z0-9_.-]+$")
}
