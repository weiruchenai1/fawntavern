package me.rerere.fawntavern.data.character

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.JsonFileDir
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.Inflater

object CharacterRepository {

    private const val CHARS_DIR = "characters"
    private const val PREFS = "character_repo"
    private const val KEY_DEFAULT_NAME = "default_card_name"
    private const val KEY_ORDER = "card_order"

    fun charsDir(context: Context): File =
        File(context.filesDir, CHARS_DIR).also { it.mkdirs() }

    /**
     * 确保内置"默认角色"空白卡存在，缺失则（重新）创建——它是角色选择面板与主界面的
     * 兜底卡，可编辑但不可删除（删除入口在 UI 隐藏，[clear] 也会跳过它）。
     * 文件名在首次创建时按当时语言固定并记录在 prefs，之后不随语言切换变化。
     * @return 默认卡的文件名
     */
    suspend fun ensureDefaultCard(context: Context, fallbackName: String): String = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_DEFAULT_NAME, null)
            ?: fallbackName.also { prefs.edit().putString(KEY_DEFAULT_NAME, it).apply() }
        val file = File(charsDir(context), "$name.json")
        if (!file.exists()) {
            val json = JSONObject()
                .put("spec", "chara_card_v2")
                .put("spec_version", "2.0")
                .put("data", JSONObject()
                    .put("name", name)
                    .put("description", "")
                    .put("first_mes", "")
                    .put("tags", JSONArray())
                    .put("alternate_greetings", JSONArray()))
            file.writeText(json.toString(2))
        }
        name
    }

    /** 默认角色卡的文件名（首启初始化前为 null）；UI 据此隐藏"删除"入口 */
    fun defaultCardName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEFAULT_NAME, null)

    /**
     * 角色卡列表：按用户保存的自定义顺序（拖拽排序）返回；未入序的新卡按字母序
     * 追加在末尾，默认卡首次出现时置顶。
     */
    suspend fun listNames(context: Context): List<String> = withContext(Dispatchers.IO) {
        val files = charsDir(context).listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = try {
            val arr = JSONArray(prefs.getString(KEY_ORDER, "[]"))
            List(arr.length()) { arr.optString(it) }
        } catch (_: Exception) { emptyList() }
        val def = prefs.getString(KEY_DEFAULT_NAME, null)
        val known = saved.filter { it in files }
        val fresh = files.filter { it !in saved }.sortedBy { it != def }  // 默认卡排在新增区最前
        known + fresh
    }

    /** 保存拖拽排序结果 */
    suspend fun saveOrder(context: Context, names: List<String>) = withContext(Dispatchers.IO) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORDER, JSONArray(names).toString()).apply()
    }

    /** 角色卡图片（与 JSON 同名的 .png）：导入 PNG 卡时保留原图，编辑器里可更换 */
    fun imageFile(context: Context, name: String): File = File(charsDir(context), "$name.png")

    /** 用相册选择的图片替换角色卡图片（统一转存为 PNG）。返回是否成功 */
    suspend fun saveImageFromUri(context: Context, name: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: return@withContext false
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext false
            imageFile(context, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (_: Exception) { false }
    }

    suspend fun deleteImage(context: Context, name: String) = withContext(Dispatchers.IO) {
        imageFile(context, name).delete()
        Unit
    }

    /** 解码角色卡图片缩略图（按目标边长采样，避免整张卡图全量解码）；无图返回 null */
    fun decodeImageThumb(context: Context, name: String, targetPx: Int = 128): Bitmap? {
        val f = imageFile(context, name)
        if (!f.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) { null }
    }

    suspend fun load(context: Context, name: String): CharacterCard = withContext(Dispatchers.IO) {
        val file = File(charsDir(context), "$name.json")
        if (!file.exists()) throw IllegalStateException("角色文件不存在: $name")
        CharacterParser.parse(JSONObject(file.readText()), name)
    }

    /** 在数据层原子更新角色卡 JSON；变换或写盘失败会抛错，原文件保持不变。 */
    suspend fun updateJson(context: Context, name: String, transform: (JSONObject) -> Unit) =
        withContext(Dispatchers.IO) {
            val file = File(charsDir(context), "$name.json")
            require(file.isFile) { "角色文件不存在: $name" }
            val json = JSONObject(file.readText())
            transform(json.optJSONObject("data") ?: json)

            val temp = File.createTempFile("${file.nameWithoutExtension}_", ".tmp", file.parentFile)
            try {
                temp.outputStream().bufferedWriter().use { writer ->
                    writer.write(json.toString(2))
                }
                try {
                    Files.move(
                        temp.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temp.delete()
            }
        }

    suspend fun import(context: Context, uri: Uri): CharacterCard = withContext(Dispatchers.IO) {
        val rawBytes = context.contentResolver.openInputStream(uri)?.readBytes()
            ?: throw IllegalStateException("无法读取文件")

        val jsonStr = extractJsonFromFile(rawBytes)
            ?: throw IllegalStateException("无法解析角色卡，请确认是 PNG/JSON 格式的 SillyTavern 角色卡")

        val json = JSONObject(jsonStr)

        val parsed = CharacterParser.parse(json)
        val displayName = JsonFileDir.queryDisplayName(context, uri)
        val requestedName = parsed.name.ifBlank {
            displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: "character_${System.currentTimeMillis()}"
        }
        val name = uniqueFileName(context, requestedName)

        val file = File(charsDir(context), "$name.json")
        file.writeText(jsonStr)

        // PNG 卡：保留原图作为角色卡图片（编辑器展示/可更换，导出 PNG 时复用）
        if (rawBytes.size >= 8 && rawBytes[0] == 0x89.toByte()) {
            imageFile(context, name).writeBytes(rawBytes)
        }

        // 提取内嵌世界书，另存为独立的世界书文件
        val charaBook = json.optJSONObject("data")?.optJSONObject("character_book")
            ?: json.optJSONObject("character_book")
        if (charaBook != null) {
            val bookName = charaBook.optString("name", "").ifBlank { "$name 世界书" }
            val worldFile = File(context.filesDir, "worldbooks/$bookName.json")
            if (!worldFile.exists()) {
                worldFile.parentFile?.mkdirs()
                worldFile.writeText(charaBook.toString(2))
            }
        }

        parsed
    }

    suspend fun delete(context: Context, name: String) = withContext(Dispatchers.IO) {
        File(charsDir(context), "$name.json").delete()
        imageFile(context, name).delete()
        Unit
    }

    /** 清空所有角色卡（内置默认角色卡除外——它是兜底卡，不可删除） */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        val keep = defaultCardName(context)
        charsDir(context).listFiles()?.forEach { if (it.nameWithoutExtension != keep) it.delete() }
        Unit
    }

    /** 导出角色卡原始 JSON 字节（与 SillyTavern 兼容，文件内容原样输出） */
    suspend fun exportJsonBytes(context: Context, name: String): ByteArray = withContext(Dispatchers.IO) {
        File(charsDir(context), "$name.json").readBytes()
    }

    /**
     * 导出为 SillyTavern 兼容 PNG：优先使用角色卡图片（导入时保留/编辑器更换的那张）；
     * 没有图片时生成一张简单卡面（深色底 + 居中角色名）。卡片 JSON 以 base64 编码
     * 嵌入 tEXt 块（关键字 "chara"），原图里旧的 chara/ccv3 块会被剥离避免双写。
     */
    suspend fun exportPngBytes(context: Context, name: String): ByteArray = withContext(Dispatchers.IO) {
        val jsonStr = File(charsDir(context), "$name.json").readText()
        val img = imageFile(context, name)
        val base = if (img.exists()) {
            img.readBytes()
        } else {
            val displayName = try {
                val j = JSONObject(jsonStr)
                (j.optJSONObject("data") ?: j).optString("name", "").ifBlank { name }
            } catch (_: Exception) { name }
            // 400×600 为 ST 卡面常规比例
            val bmp = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply {
                drawColor(0xFF2E3440.toInt())
                drawText(
                    displayName.take(10), 200f, 310f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFFECEFF4.toInt()
                        textSize = 40f
                        textAlign = Paint.Align.CENTER
                    },
                )
            }
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        embedCharaChunk(base, jsonStr)
    }

    /** 在 PNG 的 IEND 块前插入 chara(V2)+ccv3(V3) 两个 tEXt 块，并剥离已有的 chara/ccv3 块 */
    private fun embedCharaChunk(png: ByteArray, json: String): ByteArray {
        val charaText = "chara".toByteArray() + byteArrayOf(0) + Base64.encode(json.toByteArray(), Base64.NO_WRAP)
        // 派生 ccv3：spec 改为 chara_card_v3 / 3.0（解析失败则跳过 ccv3，仅写 chara）
        val ccv3Text = try {
            val v3 = JSONObject(json).put("spec", "chara_card_v3").put("spec_version", "3.0")
            "ccv3".toByteArray() + byteArrayOf(0) + Base64.encode(v3.toString().toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) { null }
        val out = ByteArrayOutputStream(png.size + charaText.size + (ccv3Text?.size ?: 0) + 24)
        out.write(png, 0, 8)  // PNG 签名
        var pos = 8
        while (pos + 12 <= png.size) {
            val len = readInt32BE(png, pos)
            val type = String(png, pos + 4, 4)
            val stale = (type == "tEXt" || type == "zTXt") && isCharaChunk(png, pos + 8, len)
            if (type == "IEND") {
                writeChunk(out, "tEXt", charaText)
                ccv3Text?.let { writeChunk(out, "tEXt", it) }
            }
            if (!stale) out.write(png, pos, 12 + len)
            pos += 12 + len
        }
        return out.toByteArray()
    }

    private fun isCharaChunk(bytes: ByteArray, dataStart: Int, len: Int): Boolean {
        if (dataStart + len > bytes.size) return false
        val nullIdx = findNull(bytes, dataStart, len)
        if (nullIdx <= 0) return false
        val keyword = String(bytes, dataStart, nullIdx - dataStart)
        return keyword == "chara" || keyword == "ccv3"
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        val typeBytes = type.toByteArray()
        out.write(int32(data.size))
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        out.write(int32(crc.value.toInt()))
    }

    private fun extractJsonFromFile(bytes: ByteArray): String? {
        // 1. 尝试直接按 JSON 解析（纯 JSON 文件）
        try { return String(bytes).also { JSONObject(it) } } catch (_: Exception) {}

        // 2. 尝试解析 PNG 块（关键字为 "chara" / "ccv3" 的 tEXt/zTXt）
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte()) {
            return extractFromPngChunks(bytes)
        }

        // 3. 兜底：在原始字节里直接搜索 "chara" 关键字
        extractBase64FromRaw(bytes)?.let { b64 ->
            try { val json = String(Base64.decode(b64, Base64.DEFAULT)); JSONObject(json); return json } catch (_: Exception) {}
        }

        return null
    }

    /** 遍历全部 PNG 块；有效 ccv3 优先，否则回退到 chara。 */
    private fun extractFromPngChunks(bytes: ByteArray): String? {
        var charaJson: String? = null
        var ccv3Json: String? = null
        var pos = 8 // 跳过 PNG 签名
        while (pos + 12 <= bytes.size) {
            val len = readInt32BE(bytes, pos)
            val type = String(bytes, pos + 4, 4)
            val dataStart = pos + 8
            if (dataStart + len > bytes.size) break

            when (type) {
                "tEXt" -> {
                    // 格式：keyword\0text
                    val nullIdx = findNull(bytes, dataStart, len)
                    if (nullIdx > 0) {
                        val keyword = String(bytes, dataStart, nullIdx - dataStart)
                        if (keyword == "chara" || keyword == "ccv3") {
                            val textStart = nullIdx + 1
                            val textLen = (dataStart + len) - textStart
                            val b64 = String(bytes, textStart, textLen).trim()
                            decodeBase64Json(b64)?.let { decoded ->
                                if (keyword == "ccv3") ccv3Json = decoded else charaJson = decoded
                            }
                        }
                    }
                }
                "zTXt" -> {
                    // 格式：keyword\0compression\0compressed_data
                    val kwEnd = findNull(bytes, dataStart, len)
                    if (kwEnd > 0) {
                        val keyword = String(bytes, dataStart, kwEnd - dataStart)
                        if (keyword == "chara" || keyword == "ccv3") {
                            val compStart = kwEnd + 2 // 跳过 null 与压缩方式字节
                            val compLen = (dataStart + len) - compStart
                            if (compLen > 0) {
                                try {
                                    val inflater = Inflater()
                                    inflater.setInput(bytes, compStart, compLen)
                                    val out = ByteArrayOutputStream()
                                    val buf = ByteArray(4096)
                                    while (!inflater.finished()) out.write(buf, 0, inflater.inflate(buf))
                                    inflater.end()
                                    val b64 = String(out.toByteArray()).trim()
                                    decodeBase64Json(b64)?.let { decoded ->
                                        if (keyword == "ccv3") ccv3Json = decoded else charaJson = decoded
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
            pos = dataStart + len + 4 // +4 为 CRC
        }
        return ccv3Json ?: charaJson
    }

    private fun uniqueFileName(context: Context, requestedName: String): String {
        val safe = requestedName
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim().trim('.')
            .ifBlank { "character_${System.currentTimeMillis()}" }
        var candidate = safe
        var suffix = 2
        while (File(charsDir(context), "$candidate.json").exists() || imageFile(context, candidate).exists()) {
            candidate = "$safe ($suffix)"
            suffix++
        }
        return candidate
    }

    /** 在原始字节中搜索 "chara\0"，提取其后的 base64。 */
    private fun extractBase64FromRaw(bytes: ByteArray): String? {
        val marker = "chara ".toByteArray()
        val idx = findBytes(bytes, marker) ?: return null
        val start = idx + marker.size
        var end = start
        while (end < bytes.size && bytes[end] != 0x00.toByte() && bytes[end] >= 0x20.toByte()) end++
        return if (end > start) String(bytes, start, end - start).trim() else null
    }

    private fun decodeBase64Json(b64: String): String? {
        return try { String(Base64.decode(b64, Base64.DEFAULT)).also { JSONObject(it) } } catch (_: Exception) { null }
    }

    private fun readInt32BE(bytes: ByteArray, pos: Int): Int =
        ((bytes[pos].toInt() and 0xFF) shl 24) or
        ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
        ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
        (bytes[pos + 3].toInt() and 0xFF)

    private fun findNull(bytes: ByteArray, start: Int, len: Int): Int {
        var i = start
        val end = start + len
        while (i < end && bytes[i] != 0.toByte()) i++
        return if (i < end) i else -1
    }

    private fun findBytes(haystack: ByteArray, needle: ByteArray): Int? {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }
}
