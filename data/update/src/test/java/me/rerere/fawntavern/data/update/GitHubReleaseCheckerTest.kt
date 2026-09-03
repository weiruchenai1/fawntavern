package me.rerere.fawntavern.data.update

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubReleaseCheckerTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun newerReleaseUsesApkDownloadUrl() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "tag_name": "v0.2.0",
                      "html_url": "https://github.com/weiruchenai1/fawntavern/releases/tag/v0.2.0",
                      "assets": [{
                        "name": "fawntavern-release.apk",
                        "browser_download_url": "https://github.com/download/fawntavern-release.apk"
                      }]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val checker = GitHubReleaseChecker(latestReleaseUrl = server.url("/latest").toString())

        val result = checker.check("0.1.0")

        assertTrue(result is UpdateCheckResult.Available)
        result as UpdateCheckResult.Available
        assertEquals("0.2.0", result.latestVersion)
        assertEquals("https://github.com/download/fawntavern-release.apk", result.downloadUrl)
    }

    @Test
    fun sameReleaseIsUpToDate() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "tag_name": "v0.2.0",
                      "html_url": "https://github.com/weiruchenai1/fawntavern/releases/tag/v0.2.0",
                      "assets": []
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val checker = GitHubReleaseChecker(latestReleaseUrl = server.url("/latest").toString())

        assertEquals(UpdateCheckResult.UpToDate, checker.check("0.2.0"))
    }

    @Test
    fun selectsApkMatchingDeviceAbi() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "tag_name": "v0.2.0",
                      "html_url": "https://github.com/weiruchenai1/fawntavern/releases/tag/v0.2.0",
                      "assets": [
                        {
                          "name": "FawnTavern-0.2.0-arm64-v8a.apk",
                          "browser_download_url": "https://github.com/download/arm64.apk"
                        },
                        {
                          "name": "FawnTavern-0.2.0-x86_64.apk",
                          "browser_download_url": "https://github.com/download/x86_64.apk"
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val checker = GitHubReleaseChecker(
            latestReleaseUrl = server.url("/latest").toString(),
            supportedAbis = listOf("x86_64"),
        )

        val result = checker.check("0.1.0") as UpdateCheckResult.Available

        assertEquals("https://github.com/download/x86_64.apk", result.downloadUrl)
    }

    @Test
    fun stableReleaseIsNewerThanMatchingBeta() {
        val stable = SemanticVersion.parse("v0.2.0")!!
        val beta = SemanticVersion.parse("0.2.0-beta.1")!!

        assertTrue(stable > beta)
        assertTrue(SemanticVersion.parse("0.2.0-beta.2")!! > beta)
        assertTrue(SemanticVersion.parse("0.3.0")!! > stable)
    }
}
