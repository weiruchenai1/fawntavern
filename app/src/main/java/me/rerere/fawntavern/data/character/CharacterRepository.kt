package me.rerere.fawntavern.data.character

import androidx.core.content.edit
import androidx.core.graphics.createBitmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.JsonFileDir
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.regex.RegexSet
import me.rerere.fawntavern.data.regex.RegexSetRepository
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.data.worldbook.WorldBookParser
import me.rerere.fawntavern.data.worldbook.WorldBookRepository
import me.rerere.fawntavern.data.worldbook.WorldBookSerializer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Inflater

object CharacterRepository {

    private const val CHARS_DIR = "characters"
    private const val PREFS = "character_repo"
    private const val KEY_DEFAULT_NAME = "default_card_name"
    private const val KEY_ORDER = "card_order"
    private val mutationMutex = Mutex()

    fun charsDir(context: Context): File =
        File(context.filesDir, CHARS_DIR).also { it.mkdirs() }

    /**
     * 确保内置"默认角色"空白卡存在，缺失则（重新）创建——它是角色选择面板与主界面的
     * 兜底卡，可编辑但不可删除（删除入口在 UI 隐藏，[clear] 也会跳过它）。
     * 文件名在首次创建时按当时语言固定并记录在 prefs，之后不随语言切换变化。
     * @return 默认卡的文件名
     */
    suspend fun ensureDefaultCard(
        context: Context,
        fallbackName: String,
        defaultPresetId: String = "",
    ): String = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val name = prefs.getString(KEY_DEFAULT_NAME, null)
                ?: fallbackName.also { value -> prefs.edit { putString(KEY_DEFAULT_NAME, value) } }
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
                        .put("alternate_greetings", JSONArray())
                        .apply {
                            if (defaultPresetId.isNotBlank()) put("linked_preset_id", defaultPresetId)
                        })
                JsonFileDir.atomicWriteText(file, json.toString(2))
            } else if (defaultPresetId.isNotBlank()) {
                val json = JSONObject(file.readText())
                val data = json.optJSONObject("data") ?: json
                if (!data.has("linked_preset_id")) {
                    data.put("linked_preset_id", defaultPresetId)
                    JsonFileDir.atomicWriteText(file, json.toString(2))
                }
            }
            name
        }
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
            .edit { putString(KEY_ORDER, JSONArray(names).toString()) }
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

    /** 创建一个空白的 SillyTavern v2 角色卡。 */
    suspend fun create(
        context: Context,
        requestedName: String,
        defaultPresetId: String = "",
    ): CharacterCard = withContext(Dispatchers.IO) {
        val displayName = requestedName.trim()
        require(displayName.isNotBlank()) { "角色名称不能为空" }
        mutationMutex.withLock {
            val name = uniqueFileName(context, displayName)
            val json = JSONObject()
                .put("spec", "chara_card_v2")
                .put("spec_version", "2.0")
                .put("data", JSONObject()
                    .put("name", displayName)
                    .put("description", "")
                    .put("personality", "")
                    .put("scenario", "")
                    .put("first_mes", "")
                    .put("mes_example", "")
                    .put("tags", JSONArray())
                    .put("alternate_greetings", JSONArray())
                    .apply {
                        if (defaultPresetId.isNotBlank()) put("linked_preset_id", defaultPresetId)
                    })
            JsonFileDir.atomicWriteText(File(charsDir(context), "$name.json"), json.toString(2))
            CharacterParser.parse(json, name)
        }
    }

    /** 在数据层原子更新角色卡 JSON；变换或写盘失败会抛错，原文件保持不变。 */
    suspend fun updateJson(context: Context, name: String, transform: (JSONObject) -> Unit) =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val file = File(charsDir(context), "$name.json")
                require(file.isFile) { "角色文件不存在: $name" }
                val json = JSONObject(file.readText())
                transform(json.optJSONObject("data") ?: json)
                JsonFileDir.atomicWriteText(file, json.toString(2))
            }
        }

    suspend fun import(
        context: Context,
        uri: Uri,
        defaultPresetId: String = "",
    ): CharacterCard = withContext(Dispatchers.IO) {
        val rawBytes = context.contentResolver.openInputStream(uri)?.readBytes()
            ?: throw IllegalStateException("无法读取文件")

        val jsonStr = extractJsonFromFile(rawBytes)
            ?: throw IllegalStateException("无法解析角色卡，请确认是 PNG/JSON 格式的 SillyTavern 角色卡")

        val json = JSONObject(jsonStr)
        val data = json.optJSONObject("data") ?: json
        if (!data.has("linked_preset_id") && defaultPresetId.isNotBlank()) {
            data.put("linked_preset_id", defaultPresetId)
        }

        val parsed = CharacterParser.parse(json)
        val displayName = JsonFileDir.queryDisplayName(context, uri)
        val requestedName = parsed.name.ifBlank {
            displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: "character_${System.currentTimeMillis()}"
        }
        mutationMutex.withLock {
            val baseName = safeFileName(requestedName)
            val name = uniqueFileName(context, requestedName)
            if (name != baseName) data.put("name", name)
            val cardFile = File(charsDir(context), "$name.json")
            val pngFile = imageFile(context, name)
            var createdImage = false
            var createdWorldBook: WorldBook? = null
            var createdRegexSet: RegexSet? = null
            try {
                // The card JSON is the commit marker. Side files are written first and cleaned up
                // if anything fails before the final atomic JSON replacement.
                if (rawBytes.size >= 8 && rawBytes[0] == 0x89.toByte()) {
                    JsonFileDir.atomicWriteBytes(pngFile, rawBytes)
                    createdImage = true
                }

                val charaBook = json.optJSONObject("data")?.optJSONObject("character_book")
                    ?: json.optJSONObject("character_book")
                if (charaBook != null) {
                    val bookName = charaBook.optString("name", "").ifBlank { "$name 世界书" }
                    val extractedBook = WorldBookRepository.createFromCharacterBook(context, bookName, charaBook)
                    createdWorldBook = extractedBook
                    data.put("enabled_world_book_ids", JSONArray().put(extractedBook.id))
                }

                val embeddedRegex = data.optJSONObject("extensions")?.optJSONArray("regex_scripts")
                if (embeddedRegex != null) {
                    // 内嵌正则与 character_book 同理：抽成独立局部正则，卡内那份只留作导出载荷。
                    // 空数组明确表示这张导入卡没有关联的局部正则。
                    createdRegexSet = if (embeddedRegex.length() > 0) {
                        RegexSetRepository.createFrom(context, "$name 正则", embeddedRegex)
                    } else {
                        null
                    }
                    data.put("enabled_regex_ids", JSONArray().apply {
                        createdRegexSet?.let { put(it.id) }
                    })
                }

                JsonFileDir.atomicWriteText(cardFile, json.toString(2))
                CharacterParser.parse(json, name)
            } catch (error: Exception) {
                if (!cardFile.exists()) {
                    if (createdImage) pngFile.delete()
                    createdWorldBook?.let { WorldBookRepository.delete(context, it.name) }
                    createdRegexSet?.let { RegexSetRepository.delete(context, it.name) }
                }
                throw error
            }
        }
    }

    /** 除 [excluding] 外其余角色仍关联的世界书 ID，用于避免删除共享资源。 */
    suspend fun referencedWorldBookIds(context: Context, excluding: String): Set<String> =
        withContext(Dispatchers.IO) {
            charsDir(context).listFiles()
                ?.asSequence()
                ?.filter { it.extension == "json" && it.nameWithoutExtension != excluding }
                ?.flatMap { file ->
                    runCatching {
                        val card = CharacterParser.parse(
                            JSONObject(file.readText()),
                            file.nameWithoutExtension,
                        )
                        card.enabledWorldBookIds.asSequence()
                    }.getOrDefault(emptySequence())
                }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
        }

    /** 除 [excluding] 外其余角色仍关联的正则 ID，用于避免删除共享资源。 */
    suspend fun referencedRegexIds(context: Context, excluding: String): Set<String> =
        withContext(Dispatchers.IO) {
            charsDir(context).listFiles()
                ?.asSequence()
                ?.filter { it.extension == "json" && it.nameWithoutExtension != excluding }
                ?.flatMap { file ->
                    runCatching {
                        CharacterParser.parse(JSONObject(file.readText()), file.nameWithoutExtension)
                            .enabledRegexIds.asSequence()
                    }.getOrDefault(emptySequence())
                }
                ?.filter(String::isNotBlank)
                ?.toSet()
                .orEmpty()
        }

    /** 正则按不可变 ID 关联，重命名只改文件名。 */
    suspend fun renameRegexSet(
        context: Context,
        oldName: String,
        newName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!JsonFileDir.isValidName(newName)) return@withContext false
        RegexSetRepository.rename(context, oldName, newName)
    }

    /** 删除独立正则集，并从所有角色的关联数组中移除它。 */
    suspend fun deleteRegexSet(context: Context, name: String) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val id = RegexSetRepository.load(context, name).id
            val updates = associationRemovalUpdates(context, "enabled_regex_ids", id)
            writeAssociationUpdates(updates) { RegexSetRepository.delete(context, name) }
        }
    }

    suspend fun deleteWorldBook(context: Context, name: String) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val id = WorldBookRepository.load(context, name).id
            val updates = associationRemovalUpdates(context, "enabled_world_book_ids", id)
            writeAssociationUpdates(updates) { WorldBookRepository.delete(context, name) }
        }
    }

    suspend fun deletePreset(context: Context, name: String) = withContext(Dispatchers.IO) {
        if (name == PresetRepository.defaultPresetName(context)) return@withContext
        mutationMutex.withLock {
            val id = PresetRepository.load(context, name).id
            val updates = scalarAssociationRemovalUpdates(context, "linked_preset_id", id)
            writeAssociationUpdates(updates) { PresetRepository.delete(context, name) }
        }
    }

    private fun associationRemovalUpdates(
        context: Context,
        field: String,
        id: String,
    ): List<Triple<File, String, String>> = charsDir(context).listFiles()
        ?.filter { it.extension == "json" }
        ?.mapNotNull { file ->
            val original = file.readText()
            val json = JSONObject(original)
            val data = json.optJSONObject("data") ?: json
            val current = data.optJSONArray(field) ?: return@mapNotNull null
            val ids = (0 until current.length()).map { current.optString(it, "") }
            if (id !in ids) return@mapNotNull null
            data.put(field, JSONArray(ids.filterNot { it == id }))
            Triple(file, original, json.toString(2))
        }
        .orEmpty()

    private fun scalarAssociationRemovalUpdates(
        context: Context,
        field: String,
        id: String,
    ): List<Triple<File, String, String>> = charsDir(context).listFiles()
        ?.filter { it.extension == "json" }
        ?.mapNotNull { file ->
            val original = file.readText()
            val json = JSONObject(original)
            val data = json.optJSONObject("data") ?: json
            if (data.optString(field, "") != id) return@mapNotNull null
            data.put(field, "")
            Triple(file, original, json.toString(2))
        }
        .orEmpty()

    private suspend fun writeAssociationUpdates(
        updates: List<Triple<File, String, String>>,
        deleteResource: suspend () -> Unit,
    ) {
        val written = mutableListOf<Pair<File, String>>()
        try {
            updates.forEach { (file, original, updated) ->
                JsonFileDir.atomicWriteText(file, updated)
                written += file to original
            }
            deleteResource()
        } catch (error: Exception) {
            written.asReversed().forEach { (file, original) ->
                runCatching { JsonFileDir.atomicWriteText(file, original) }
            }
            throw error
        }
    }

    suspend fun delete(context: Context, name: String) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            File(charsDir(context), "$name.json").delete()
            imageFile(context, name).delete()
            Unit
        }
    }

    /** 清空所有角色卡（内置默认角色卡除外——它是兜底卡，不可删除） */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val keep = defaultCardName(context)
            charsDir(context).listFiles()?.forEach { if (it.nameWithoutExtension != keep) it.delete() }
            Unit
        }
    }

    /** 导出角色卡 JSON（与 SillyTavern 兼容；除内嵌世界书按关联重建外，其余字段原样输出） */
    suspend fun exportJsonBytes(context: Context, name: String): ByteArray = withContext(Dispatchers.IO) {
        cardJsonForExport(context, name).toByteArray()
    }

    /**
     * 导出用的卡 JSON：把 `character_book` 和 `extensions.regex_scripts` 换成当前关联的世界书 /
     * 正则集内容。卡内那两份自导入起就不再更新，直接导出会丢掉用户后来的全部编辑（同 ST 保存时
     * 按 linked world 重新生成内嵌书）。关联项一个都读不到时保持文件原样，解析/读盘出错也回落
     * 原文，导出不因此失败。
     */
    private suspend fun cardJsonForExport(context: Context, name: String): String {
        val text = File(charsDir(context), "$name.json").readText()
        return runCatching {
            val json = JSONObject(text)
            val data = json.optJSONObject("data") ?: json
            val card = CharacterParser.parse(json, name)
            var changed = false

            val sources = card.enabledWorldBookIds
                .filter { it.isNotBlank() }
                .distinct()
                .mapNotNull { bookId ->
                    val book = runCatching { WorldBookRepository.loadById(context, bookId) }.getOrNull()
                        ?: return@mapNotNull null
                    val bookName = book.name
                    val file = JsonFileDir.file(context, WorldBookRepository.WORLD_DIR, bookName)
                    if (!file.isFile) return@mapNotNull null
                    val raw = JSONObject(file.readText())
                    WorldBookSerializer.Source(raw, WorldBookParser.parse(raw, bookName))
                }
            if (sources.isNotEmpty()) {
                data.put(
                    "character_book",
                    WorldBookSerializer.toCharacterBook(sources.first().book.name, sources),
                )
                val extensions = data.optJSONObject("extensions")
                    ?: JSONObject().also { data.put("extensions", it) }
                extensions.put("world", sources.first().book.name)
                changed = true
            } else if (card.enabledWorldBookIds.isEmpty() && data.has("enabled_world_book_ids")) {
                data.remove("character_book")
                data.optJSONObject("extensions")?.remove("world")
                changed = true
            }

            // 关联多个正则集就合并成一个数组：ST 卡内只有 extensions.regex_scripts 一处能放
            val associatedSets = card.enabledRegexIds.filter { it.isNotBlank() }.distinct()
            val merged = JSONArray()
            var foundAssociatedSet = false
            associatedSets.forEach { setId ->
                val set = runCatching { RegexSetRepository.loadById(context, setId) }.getOrNull()
                if (set != null && !set.global) {
                    foundAssociatedSet = true
                    RegexSetRepository.rawScripts(context, set.name)?.let { arr ->
                        for (i in 0 until arr.length()) merged.put(arr.opt(i))
                    }
                }
            }
            if (associatedSets.isEmpty() && data.has("enabled_regex_ids")) {
                data.optJSONObject("extensions")?.remove("regex_scripts")
                changed = true
            } else if (foundAssociatedSet) {
                val ext = data.optJSONObject("extensions")
                    ?: JSONObject().also { data.put("extensions", it) }
                ext.put("regex_scripts", merged)
                changed = true
            }

            if (changed) json.toString(2) else text
        }.getOrDefault(text)
    }

    /**
     * 导出为 SillyTavern 兼容 PNG：优先使用角色卡图片（导入时保留/编辑器更换的那张）；
     * 没有图片时生成一张简单卡面（深色底 + 居中角色名）。卡片 JSON 以 base64 编码
     * 嵌入 tEXt 块（关键字 "chara"），原图里旧的 chara/ccv3 块会被剥离避免双写。
     */
    suspend fun exportPngBytes(context: Context, name: String): ByteArray = withContext(Dispatchers.IO) {
        val jsonStr = cardJsonForExport(context, name)
        val img = imageFile(context, name)
        val base = if (img.exists()) {
            img.readBytes()
        } else {
            val displayName = try {
                val j = JSONObject(jsonStr)
                (j.optJSONObject("data") ?: j).optString("name", "").ifBlank { name }
            } catch (_: Exception) { name }
            // 400×600 为 ST 卡面常规比例
            val bmp = createBitmap(400, 600, Bitmap.Config.ARGB_8888)
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
        val safe = safeFileName(requestedName)
        var candidate = safe
        var suffix = 2
        while (File(charsDir(context), "$candidate.json").exists() || imageFile(context, candidate).exists()) {
            candidate = "$safe ($suffix)"
            suffix++
        }
        return candidate
    }

    private fun safeFileName(requestedName: String): String = requestedName
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim().trim('.')
            .ifBlank { "character_${System.currentTimeMillis()}" }

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
