package me.rerere.fawntavern.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PluginInstallerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PluginRepository.root(context).deleteRecursively()
    }

    // ── 用例构造 ──────────────────────────────────────────

    private fun manifestJson(
        id: String = "dev.test.plugin",
        version: String = "1.0.0",
        entry: String = "index.js",
        capabilities: String = "[\"prompt-contributor\"]",
        extra: String = "",
    ) = """
        { "id": "$id", "name": "Test", "version": "$version",
          "entry": "$entry", "capabilities": $capabilities $extra }
    """.trimIndent()

    /** [topDir] 非空时模拟 GitHub zip 的顶层 `repo-branch/` 目录。 */
    private fun zipOf(files: Map<String, String>, topDir: String = ""): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(if (topDir.isBlank()) name else "$topDir/$name"))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun defaultZip(
        version: String = "1.0.0",
        entryBody: String = "export const v = 1;",
        topDir: String = "",
    ) = zipOf(
        mapOf("manifest.json" to manifestJson(version = version), "index.js" to entryBody),
        topDir = topDir,
    )

    private fun install(bytes: ByteArray, appVersion: String = "1.0.0") = runBlocking {
        PluginInstaller.installFromZip(context, ByteArrayInputStream(bytes), appVersion)
    }

    private fun installFailure(bytes: ByteArray, appVersion: String = "1.0.0"): Throwable =
        runCatching { install(bytes, appVersion) }.exceptionOrNull()
            ?: error("安装本应失败但成功了")

    // ── 正常路径 ──────────────────────────────────────────

    @Test
    fun installsPluginFromZip() {
        val installed = install(defaultZip())

        assertEquals("dev.test.plugin", installed.manifest.id)
        assertEquals("1.0.0", installed.manifest.version)
        assertEquals(PluginRepository.SOURCE_LOCAL, installed.meta.source)
        assertTrue(File(installed.dir, "index.js").isFile)
        assertTrue(File(installed.dir, ".install.json").isFile)
        assertEquals(1, runBlocking { PluginRepository.list(context) }.size)
    }

    @Test
    fun installsGenerationLifecyclePlugin() {
        val bytes = zipOf(
            mapOf(
                "manifest.json" to manifestJson(capabilities = "[\"generation-lifecycle\"]"),
                "index.js" to "export const v = 1;",
            )
        )

        val installed = install(bytes)

        assertEquals(listOf("generation-lifecycle"), installed.manifest.capabilities)
    }

    /** GitHub zip 多包一层 `repo-branch/`，必须剥掉。 */
    @Test
    fun stripsGitHubTopLevelDirectory() {
        val installed = install(defaultZip(topDir = "my-plugin-main"))

        assertTrue(File(installed.dir, "index.js").isFile)
        assertFalse(File(installed.dir, "my-plugin-main").exists())
    }

    @Test
    fun overwriteInstallUpdatesVersion() {
        install(defaultZip(version = "1.0.0"))
        val updated = install(defaultZip(version = "1.2.0", entryBody = "export const v = 2;"))

        assertEquals("1.2.0", updated.manifest.version)
        assertEquals("export const v = 2;", File(updated.dir, "index.js").readText())
        assertEquals(1, runBlocking { PluginRepository.list(context) }.size)
    }

    /** 覆盖安装失败时，旧版本必须原封不动 —— 这是整个换入流程存在的理由。 */
    @Test
    fun failedOverwriteKeepsOldVersion() {
        val old = install(defaultZip(version = "1.0.0", entryBody = "old"))

        // 新包 manifest 坏掉：解压得动，但解析不过
        val broken = zipOf(mapOf("manifest.json" to "{ not json", "index.js" to "new"))
        assertTrue(installFailure(broken) is PluginFormatException)

        val after = runBlocking { PluginRepository.get(context, "dev.test.plugin") }
        assertNotNull(after)
        assertEquals("1.0.0", after!!.manifest.version)
        assertEquals("old", File(old.dir, "index.js").readText())
    }

    @Test
    fun uninstallRemovesDirectory() {
        val installed = install(defaultZip())

        assertTrue(runBlocking { PluginInstaller.uninstall(context, "dev.test.plugin") })
        assertFalse(installed.dir.exists())
        assertTrue(runBlocking { PluginRepository.list(context) }.isEmpty())
    }

    // ── 解压防护 ──────────────────────────────────────────

    @Test
    fun rejectsZipSlip() {
        val bytes = zipOf(
            mapOf(
                "manifest.json" to manifestJson(),
                "index.js" to "x",
                "../evil.js" to "pwned",
            )
        )
        assertTrue(installFailure(bytes) is PluginFormatException)
        assertFalse(File(PluginRepository.root(context).parentFile, "evil.js").exists())
    }

    @Test
    fun rejectsAbsoluteEntryPath() {
        val bytes = zipOf(mapOf("manifest.json" to manifestJson(), "/etc/evil.js" to "pwned"))
        assertTrue(installFailure(bytes) is PluginFormatException)
    }

    @Test
    fun rejectsOversizedFile() {
        val big = "x".repeat((PluginArchive.MAX_ENTRY_BYTES + 1024).toInt())
        val bytes = zipOf(mapOf("manifest.json" to manifestJson(), "index.js" to big))
        assertTrue(installFailure(bytes) is PluginFormatException)
    }

    @Test
    fun rejectsTooManyFiles() {
        val files = buildMap {
            put("manifest.json", manifestJson())
            put("index.js", "x")
            repeat(PluginArchive.MAX_FILES + 5) { put("src/f$it.js", "x") }
        }
        assertTrue(installFailure(zipOf(files)) is PluginFormatException)
    }

    /** 白名单外的文件跳过写盘，但不该让整个安装失败 —— 真实仓库里全是 .yml/.gitignore/LICENSE。 */
    @Test
    fun skipsNonWhitelistedFilesWithoutFailing() {
        val bytes = zipOf(
            mapOf(
                "manifest.json" to manifestJson(),
                "index.js" to "x",
                "README.md" to "doc",
                ".github/workflows/ci.yml" to "on: push",
                "LICENSE" to "AGPL",
            )
        )
        val installed = install(bytes)

        assertTrue(File(installed.dir, "README.md").isFile)
        assertFalse(File(installed.dir, ".github").exists())
        assertFalse(File(installed.dir, "LICENSE").exists())
    }

    // ── manifest 校验 ─────────────────────────────────────

    @Test
    fun rejectsMissingManifest() {
        assertTrue(installFailure(zipOf(mapOf("index.js" to "x"))) is PluginFormatException)
    }

    @Test
    fun rejectsInvalidId() {
        val bytes = zipOf(mapOf("manifest.json" to manifestJson(id = "../escape"), "index.js" to "x"))
        assertTrue(installFailure(bytes) is PluginFormatException)
    }

    @Test
    fun rejectsUnknownCapability() {
        val bytes = zipOf(
            mapOf(
                "manifest.json" to manifestJson(capabilities = "[\"telepathy\"]"),
                "index.js" to "x",
            )
        )
        assertTrue(installFailure(bytes) is PluginFormatException)
    }

    @Test
    fun rejectsReservedButUnavailableCapability() {
        val bytes = zipOf(
            mapOf(
                "manifest.json" to manifestJson(capabilities = "[\"quick-replies\"]"),
                "index.js" to "x",
            )
        )
        assertTrue(installFailure(bytes) is PluginFormatException)
    }

    @Test
    fun rejectsMissingEntryFile() {
        val bytes = zipOf(mapOf("manifest.json" to manifestJson(entry = "main.js"), "index.js" to "x"))
        assertTrue(installFailure(bytes) is PluginFormatException)
    }

    @Test
    fun rejectsEntryEscapingMirror() {
        val bytes = zipOf(mapOf("manifest.json" to manifestJson(entry = "../../evil.js"), "index.js" to "x"))
        assertTrue(installFailure(bytes) is PluginFormatException)
    }

    @Test
    fun rejectsIncompatibleAppVersion() {
        val bytes = zipOf(
            mapOf(
                "manifest.json" to manifestJson(extra = ", \"minAppVersion\": \"9.0.0\""),
                "index.js" to "x",
            )
        )
        assertTrue(installFailure(bytes, appVersion = "1.0.0") is PluginFormatException)
        assertNull(runBlocking { PluginRepository.get(context, "dev.test.plugin") })
    }

    @Test
    fun rejectsBuiltinNamespace() {
        val bytes = zipOf(
            mapOf(
                "manifest.json" to manifestJson(id = "builtin.summarize"),
                "index.js" to "x",
            )
        )
        assertTrue(installFailure(bytes) is PluginFormatException)
    }

    @Test
    fun rejectsUnknownPermission() {
        val bytes = zipOf(
            mapOf(
                "manifest.json" to manifestJson(extra = ", \"permissions\": [\"filesystem.read\"]"),
                "index.js" to "x",
            )
        )
        assertTrue(installFailure(bytes) is PluginFormatException)
    }

    @Test
    fun rejectsReservedButUnavailablePermission() {
        val bytes = zipOf(
            mapOf(
                "manifest.json" to manifestJson(extra = ", \"permissions\": [\"model.call\"]"),
                "index.js" to "x",
            )
        )
        assertTrue(installFailure(bytes) is PluginFormatException)
    }

    @Test
    fun rejectsEntryLargerThanRuntimeLimit() {
        val bytes = zipOf(
            mapOf(
                "manifest.json" to manifestJson(),
                "index.js" to "x".repeat(385 * 1024),
            )
        )
        assertTrue(installFailure(bytes) is PluginFormatException)
    }

    /** 失败后中转目录必须清干净，否则下次安装会带上上次的残留文件。 */
    @Test
    fun failedInstallCleansStaging() {
        installFailure(zipOf(mapOf("index.js" to "x")))

        assertFalse(PluginRepository.stagingDir(context).exists())
    }

    @Test
    fun interruptedSwapRestoresOldVersion() {
        val installed = install(defaultZip(version = "1.0.0", entryBody = "old"))
        val backup = PluginRepository.backupDirOf(context, installed.manifest.id)
        assertTrue(installed.dir.renameTo(backup))

        runBlocking { PluginInstaller.recoverInterruptedInstalls(context) }

        val restored = runBlocking { PluginRepository.get(context, installed.manifest.id) }
        assertNotNull(restored)
        assertEquals("1.0.0", restored!!.manifest.version)
        assertEquals("old", File(restored.dir, "index.js").readText())
        assertFalse(backup.exists())
    }

    @Test
    fun completedSwapRemovesOldBackupAndStaging() {
        val installed = install(defaultZip(version = "2.0.0", entryBody = "new"))
        val backup = PluginRepository.backupDirOf(context, installed.manifest.id)
        backup.mkdirs()
        File(backup, "stale.js").writeText("old")
        val staging = PluginRepository.stagingDir(context)
        staging.mkdirs()
        File(staging, "partial.js").writeText("partial")

        runBlocking { PluginInstaller.recoverInterruptedInstalls(context) }

        assertTrue(installed.dir.isDirectory)
        assertFalse(backup.exists())
        assertFalse(staging.exists())
    }
}
