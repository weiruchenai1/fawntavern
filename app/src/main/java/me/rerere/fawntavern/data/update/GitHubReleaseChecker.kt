package me.rerere.fawntavern.data.update

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.api.Http
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(
        val latestVersion: String,
        val downloadUrl: String,
    ) : UpdateCheckResult
}

internal class GitHubReleaseChecker(
    private val client: OkHttpClient = Http.client,
    private val latestReleaseUrl: String = LATEST_RELEASE_URL,
) {
    suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(latestReleaseUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "FawnTavern-Android")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub Releases returned HTTP ${response.code}")
            }
            parseResult(currentVersion, response.body.string())
        }
    }

    internal fun parseResult(currentVersion: String, responseJson: String): UpdateCheckResult {
        val current = SemanticVersion.parse(currentVersion)
            ?: throw IOException("Invalid current version")
        val release = JSONObject(responseJson)
        val tag = release.optString("tag_name")
        val latest = SemanticVersion.parse(tag)
            ?: throw IOException("Invalid release version")
        if (latest <= current) return UpdateCheckResult.UpToDate

        val releasePage = release.optString("html_url")
            .takeIf(::isHttpsUrl)
            ?: throw IOException("Invalid release URL")
        val assets = release.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
                apkUrl = asset.optString("browser_download_url").takeIf(::isHttpsUrl)
                if (apkUrl != null) break
            }
        }
        return UpdateCheckResult.Available(
            latestVersion = tag.removePrefix("v").removePrefix("V"),
            downloadUrl = apkUrl ?: releasePage,
        )
    }

    private fun isHttpsUrl(value: String): Boolean = value.startsWith("https://")

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/weiruchenai1/fawntavern/releases/latest"
    }
}

internal data class SemanticVersion(
    private val numbers: List<Int>,
    private val preRelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        for (index in 0 until maxOf(numbers.size, other.numbers.size)) {
            val compared = numbers.getOrElse(index) { 0 }
                .compareTo(other.numbers.getOrElse(index) { 0 })
            if (compared != 0) return compared
        }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return when {
                preRelease.isEmpty() && other.preRelease.isEmpty() -> 0
                preRelease.isEmpty() -> 1
                else -> -1
            }
        }
        for (index in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val left = preRelease[index]
            val right = other.preRelease[index]
            val leftNumber = left.toIntOrNull()
            val rightNumber = right.toIntOrNull()
            val compared = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (compared != 0) return compared
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    companion object {
        fun parse(value: String): SemanticVersion? {
            val normalized = value.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('+')
            val core = normalized.substringBefore('-')
            val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
            if (numbers.isEmpty()) return null
            val preRelease = normalized.substringAfter('-', "")
                .takeIf(String::isNotBlank)
                ?.split('.')
                ?: emptyList()
            return SemanticVersion(numbers, preRelease)
        }
    }
}
