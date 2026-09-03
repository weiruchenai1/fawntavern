package me.rerere.fawntavern.plugin

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 插件压缩包解压（GitHub codeload zip 与本地导入 zip 共用这一条路径）。
 *
 * 解压的是**完全不可信的输入**，四条防护缺一不可：路径越界、单文件/总量/文件数上限、扩展名白名单。
 * 白名单外的 entry 仍然会被读完再丢弃 —— 不读就跳过等于把 zip bomb 放进白名单之外的文件里绕过总量限制。
 */
internal object PluginArchive {

    const val MAX_FILES = 200
    const val MAX_ENTRY_BYTES = 2L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 10L * 1024 * 1024

    /** 扫描上限：仓库 zip 里非白名单文件（.git 配置、图片、workflow 等）可能远多于插件自身文件。 */
    private const val MAX_SCANNED_ENTRIES = 4000

    private val ALLOWED_EXTENSIONS = setOf("js", "json", "md", "png", "svg")

    /** 解压到 [staging]（调用方负责该目录的创建与失败清理）。 */
    fun extract(input: InputStream, staging: File) {
        staging.mkdirs()
        var scanned = 0
        var written = 0
        var totalBytes = 0L
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val current: ZipEntry = entry
                scanned++
                if (scanned > MAX_SCANNED_ENTRIES) throw PluginFormatException("压缩包条目过多")
                val target = if (current.isDirectory) null else validatedTarget(staging, current.name)
                if (target != null) {
                    written++
                    if (written > MAX_FILES) throw PluginFormatException("插件文件数超过 $MAX_FILES 个")
                    target.parentFile?.mkdirs()
                }
                val output = target?.outputStream()
                try {
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryBytes = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        totalBytes += read
                        if (entryBytes > MAX_ENTRY_BYTES) {
                            throw PluginFormatException("单个文件超过 ${MAX_ENTRY_BYTES / 1024 / 1024}MB: ${current.name}")
                        }
                        if (totalBytes > MAX_TOTAL_BYTES) {
                            throw PluginFormatException("插件总大小超过 ${MAX_TOTAL_BYTES / 1024 / 1024}MB")
                        }
                        output?.write(buffer, 0, read)
                    }
                } finally {
                    output?.close()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /**
     * 找出镜像根：GitHub zip 会多包一层 `repo-branch/`，本地 zip 则可能直接是内容。
     * 判据只有一条 —— manifest.json 在哪层，哪层就是根。
     */
    fun resolveRoot(staging: File): File {
        if (File(staging, "manifest.json").isFile) return staging
        val children = staging.listFiles()?.toList().orEmpty()
        val onlyDir = children.singleOrNull()?.takeIf { it.isDirectory }
        if (onlyDir != null && File(onlyDir, "manifest.json").isFile) return onlyDir
        throw PluginFormatException("压缩包根目录下没有 manifest.json")
    }

    /** 越界即抛；扩展名不在白名单返回 null（跳过写盘，但字节仍要读完计入总量）。 */
    private fun validatedTarget(staging: File, entryName: String): File? {
        val name = entryName.replace('\\', '/')
        if (name.isBlank() || name.startsWith('/') || name.contains(':')) {
            throw PluginFormatException("压缩包含非法路径: $entryName")
        }
        val segments = name.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.any { it == "." || it == ".." }) {
            throw PluginFormatException("压缩包含越界路径: $entryName")
        }
        if (name.any { it.isISOControl() }) throw PluginFormatException("压缩包含非法路径: $entryName")
        if (segments.last().substringAfterLast('.', "").lowercase() !in ALLOWED_EXTENSIONS) return null

        val root = staging.canonicalFile
        val target = File(root, segments.joinToString("/")).canonicalFile
        // 规范化后仍须在 staging 内 —— 符号链接与大小写折叠都可能让前面的字符串检查失效
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw PluginFormatException("压缩包含越界路径: $entryName")
        }
        return target
    }
}
