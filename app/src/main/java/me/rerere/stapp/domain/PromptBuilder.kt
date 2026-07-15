package me.rerere.stapp.domain

import java.io.File
import java.util.Base64
import me.rerere.stapp.data.api.ApiImage
import me.rerere.stapp.data.api.ApiMessage
import me.rerere.stapp.data.api.GenParams
import me.rerere.stapp.data.character.CharRegex
import me.rerere.stapp.data.character.CharacterCard
import me.rerere.stapp.data.character.RegexEngine
import me.rerere.stapp.data.chat.ChatMessage
import me.rerere.stapp.data.preset.PromptItem
import me.rerere.stapp.data.preset.StPreset
import me.rerere.stapp.data.worldbook.WorldBook
import me.rerere.stapp.data.worldbook.WorldBookEntry

/**
 * Prompt 拼装（SillyTavern 风格）。两步走：
 * 1. [build] —— 由角色卡 + 已加载的世界书/预设产出 [Built]（历史前后的提示块、
 *    深度注入块、发送侧正则、采样参数）。世界书条目按 constant/关键词扫描激活；
 *    预设存在时按 promptOrder 编排（marker 映射角色卡字段），否则用默认顺序。
 * 2. [assemble] —— 生成请求前把 [Built] 与聊天历史合成完整消息数组：
 *    历史逐条套发送侧正则、文件附件内联为文本块、图片编码 base64、深度注入按位插入。
 * UI 与 ViewModel 不感知拼装细节。
 */
internal object PromptBuilder {

    /** 世界书关键词扫描的消息条数（从最新往前数），同 ST 的 world_info_depth 默认值；条目可用 scanDepth 覆盖 */
    private const val WI_SCAN_DEPTH = 2

    /** 单个文件附件内联进 prompt 的最大字符数 */
    private const val FILE_TEXT_MAX_CHARS = 100_000

    /** 历史之外的一个提示块 */
    data class Piece(val role: String, val content: String)

    /** 注入进聊天历史内部的提示块：depth = 0 紧跟最后一条消息，k = 倒数第 k 条之前 */
    data class DepthPiece(val role: String, val content: String, val depth: Int)

    /** [build] 的产物，生成期间不变；每次请求由 [assemble] 与最新历史合成消息数组 */
    data class Built(
        val preHistory: List<Piece> = emptyList(),
        val postHistory: List<Piece> = emptyList(),
        val depthInjections: List<DepthPiece> = emptyList(),
        val promptRegex: List<CharRegex> = emptyList(),
        val genParams: GenParams? = null,
        val userName: String = "",
        val charName: String = "",
    )

    fun build(
        card: CharacterCard?,
        userName: String,
        worldBooks: List<WorldBook> = emptyList(),
        preset: StPreset? = null,
        history: List<ChatMessage> = emptyList(),
        promptRegex: List<CharRegex> = emptyList(),
    ): Built {
        val charName = card?.name ?: ""
        val wi = activateWorldInfo(card, worldBooks, history, userName)
        val wiBefore = wi.filter { it.position == "before_char" }.map { it.content }
        val wiAfter = wi.filter { it.position != "before_char" && it.position != "at_depth" }.map { it.content }
        val wiDepth = wi.filter { it.position == "at_depth" }
            .map { DepthPiece("system", it.content, it.depth) }

        val orderToggles = preset?.promptOrder
            ?.let { po -> po.firstOrNull { it.characterId == 100001 } ?: po.firstOrNull() }
            ?.order
        val assembled = if (preset != null && !orderToggles.isNullOrEmpty()) {
            buildWithPreset(preset, orderToggles, card, wiBefore, wiAfter)
        } else {
            buildDefault(card, wiBefore, wiAfter)
        }

        val genParams = preset?.let {
            GenParams(
                temperature = it.temperature,
                topP = it.topP,
                topK = it.topK.takeIf { k -> k > 0 },
                maxTokens = it.maxTokens.takeIf { t -> t > 0 },
            )
        }

        fun macro(pieces: List<Piece>) = pieces
            .map { it.copy(content = applyMacros(it.content, charName, userName).trim()) }
            .filter { it.content.isNotBlank() }

        return Built(
            preHistory = macro(assembled.pre),
            postHistory = macro(assembled.post),
            depthInjections = (wiDepth + assembled.injections)
                .map { it.copy(content = applyMacros(it.content, charName, userName).trim()) }
                .filter { it.content.isNotBlank() },
            promptRegex = promptRegex,
            genParams = genParams,
            userName = userName,
            charName = charName,
        )
    }

    /** 请求前合成完整消息数组。baseDir 为 filesDir（附件相对路径的根），null 时跳过附件 */
    fun assemble(built: Built, history: List<ChatMessage>, baseDir: File?): List<ApiMessage> {
        val hist = history.filter { it.content.isNotBlank() || it.images.isNotEmpty() || it.files.isNotEmpty() }
        val n = hist.size
        val histMsgs = hist.mapIndexed { i, m ->
            var content = RegexEngine.applyForPrompt(
                m.content, built.promptRegex, depth = n - 1 - i, role = m.role,
                userName = built.userName, charName = built.charName,
            )
            if (m.files.isNotEmpty() && baseDir != null) {
                val blocks = m.files.mapNotNull { f ->
                    readFileText(File(baseDir, f.path))?.let { "<file name=\"${f.name}\">\n$it\n</file>" }
                }
                if (blocks.isNotEmpty()) {
                    content = (blocks.joinToString("\n\n") + "\n\n" + content).trim()
                }
            }
            ApiMessage(
                role = m.role,
                content = content,
                images = if (baseDir == null) emptyList() else m.images.mapNotNull { loadImage(baseDir, it) },
            )
        }
        // 深度注入：同一插入点的多条保持原顺序；从深到浅插入保证索引不漂移
        val spliced = histMsgs.toMutableList()
        built.depthInjections
            .groupBy { (n - it.depth).coerceIn(0, n) }
            .entries.sortedByDescending { it.key }
            .forEach { (idx, pieces) -> spliced.addAll(idx, pieces.map { ApiMessage(it.role, it.content) }) }
        return built.preHistory.map { ApiMessage(it.role, it.content) } +
            spliced +
            built.postHistory.map { ApiMessage(it.role, it.content) }
    }

    /** 替换 {{char}} / {{user}} 宏 */
    fun applyMacros(text: String, charName: String, userName: String): String = text
        .replace("{{char}}", charName, ignoreCase = true)
        .replace("{{user}}", userName, ignoreCase = true)

    // ── 拼装主体 ────────────────────────────────────────────

    private data class Assembled(
        val pre: List<Piece>,
        val post: List<Piece>,
        val injections: List<DepthPiece> = emptyList(),
    )

    /** 无预设：角色卡字段按固定顺序拼成单个 system 块（世界书插在角色定义前后） */
    private fun buildDefault(card: CharacterCard?, wiBefore: List<String>, wiAfter: List<String>): Assembled {
        card ?: return Assembled(emptyList(), emptyList())
        val parts = mutableListOf<String>()
        if (card.systemPrompt.isNotBlank()) parts += card.systemPrompt
        parts += wiBefore
        if (card.description.isNotBlank()) parts += card.description
        if (card.personality.isNotBlank()) parts += "Personality: ${card.personality}"
        if (card.scenario.isNotBlank()) parts += "Scenario: ${card.scenario}"
        parts += wiAfter
        if (card.mesExample.isNotBlank()) parts += "Example dialogue:\n${card.mesExample}"
        val pre = if (parts.isEmpty()) emptyList() else listOf(Piece("system", parts.joinToString("\n\n")))
        val post = card.postHistoryInstructions.takeIf { it.isNotBlank() }
            ?.let { listOf(Piece("system", it)) } ?: emptyList()
        return Assembled(pre, post)
    }

    /**
     * 有预设：按 promptOrder 遍历启用的条目。marker 映射角色卡字段/世界书；
     * chatHistory 是历史的占位分界；injection_position = 1 的条目转为深度注入。
     * 角色卡的 system_prompt / post_history_instructions 优先于预设的 main / jailbreak
     * （对齐 ST 的 Prefer Char. Prompt 默认行为，forbid_overrides 时除外）。
     */
    private fun buildWithPreset(
        preset: StPreset,
        order: List<me.rerere.stapp.data.preset.PromptToggle>,
        card: CharacterCard?,
        wiBefore: List<String>,
        wiAfter: List<String>,
    ): Assembled {
        val prompts = preset.prompts.associateBy { it.identifier }
        val pre = mutableListOf<Piece>()
        val post = mutableListOf<Piece>()
        val injections = mutableListOf<DepthPiece>()
        var afterChat = false
        for (t in order) {
            if (!t.enabled) continue
            val p = prompts[t.identifier] ?: continue
            val target = if (afterChat) post else pre
            if (p.marker) {
                when (p.identifier) {
                    "chatHistory" -> afterChat = true
                    "charDescription" -> card?.description?.takeIf { it.isNotBlank() }?.let { target += Piece("system", it) }
                    "charPersonality" -> card?.personality?.takeIf { it.isNotBlank() }?.let { target += Piece("system", it) }
                    "scenario" -> card?.scenario?.takeIf { it.isNotBlank() }?.let { target += Piece("system", it) }
                    "dialogueExamples" -> card?.mesExample?.takeIf { it.isNotBlank() }?.let { target += Piece("system", it) }
                    "worldInfoBefore" -> wiBefore.forEach { target += Piece("system", it) }
                    "worldInfoAfter" -> wiAfter.forEach { target += Piece("system", it) }
                    // personaDescription：应用暂无 persona 描述，跳过；未知 marker 一并跳过
                }
                continue
            }
            if (!p.enabled) continue
            val content = overrideContent(p, card)
            if (content.isBlank()) continue
            if (p.injectionPosition == 1) {
                injections += DepthPiece(p.role.ifBlank { "system" }, content, p.injectionDepth)
            } else {
                target += Piece(p.role.ifBlank { "system" }, content)
            }
        }
        return Assembled(pre, post, injections)
    }

    private fun overrideContent(p: PromptItem, card: CharacterCard?): String = when {
        p.forbidOverrides -> p.content
        p.identifier == "main" && !card?.systemPrompt.isNullOrBlank() -> card!!.systemPrompt
        p.identifier == "jailbreak" && !card?.postHistoryInstructions.isNullOrBlank() -> card!!.postHistoryInstructions
        else -> p.content
    }

    // ── 世界书激活 ──────────────────────────────────────────

    /**
     * 收集激活的世界书条目（对齐 ST checkWorldInfo 的单轮扫描）：
     * constant 条目始终激活；其余对最近 N 条消息（条目 scanDepth 覆盖，默认 [WI_SCAN_DEPTH]）
     * 做关键词匹配 —— key 支持 `/pattern/flags` 正则字面量，普通串按大小写不敏感的包含匹配
     * （条目可覆盖 caseSensitive/matchWholeWords）；主关键词命中后按 selectiveLogic 校验次级
     * 关键词；probability < 100 时掷骰。结果按 insertionOrder 升序注入（同 ST 降序 sort +
     * unshift 的最终效果）。未实现：递归激活、minActivations、token 预算、inclusion group。
     * 角色卡内嵌条目与已提取成独立世界书的同内容条目去重（导入时两处都会存一份）。
     */
    private fun activateWorldInfo(
        card: CharacterCard?,
        worldBooks: List<WorldBook>,
        history: List<ChatMessage>,
        userName: String,
    ): List<WorldBookEntry> {
        val loaded = worldBooks.flatMap { it.entries.values }
        val loadedContents = loaded.mapTo(HashSet()) { it.content }
        val embedded = card?.worldBookEntries.orEmpty()
            .filter { it.content.isNotBlank() && it.content !in loadedContents }
            .map {
                WorldBookEntry(
                    id = it.id, keys = it.keys, comment = it.comment, content = it.content,
                    enabled = it.enabled, position = normalizeCardPosition(it.position),
                    insertionOrder = it.insertionOrder, constant = it.constant,
                    keySecondary = it.keySecondary, selectiveLogic = it.selectiveLogic,
                    probability = it.probability, caseSensitive = it.caseSensitive,
                )
            }
        val messages = history.map { it.content }.filter { it.isNotBlank() }
        val charName = card?.name ?: ""
        return (embedded + loaded)
            .filter { entryActive(it, messages, charName, userName) }
            .sortedBy { it.insertionOrder }
    }

    private fun entryActive(e: WorldBookEntry, messages: List<String>, charName: String, userName: String): Boolean {
        if (!e.enabled || e.content.isBlank()) return false
        val triggered = when {
            e.constant -> true
            e.keys.isEmpty() -> false
            else -> {
                val depth = e.scanDepth ?: WI_SCAN_DEPTH
                val scanText = messages.takeLast(depth).joinToString("\n")
                val primary = e.keys.any { matchKey(scanText, it, e, charName, userName) }
                when {
                    !primary -> false
                    e.keySecondary.isEmpty() -> true
                    else -> {
                        val matches = e.keySecondary.map { matchKey(scanText, it, e, charName, userName) }
                        when (e.selectiveLogic) {
                            1 -> !matches.all { it }   // NOT_ALL
                            2 -> matches.none { it }   // NOT_ANY
                            3 -> matches.all { it }    // AND_ALL
                            else -> matches.any { it } // AND_ANY
                        }
                    }
                }
            }
        }
        if (!triggered) return false
        // 激活概率：每次生成掷骰（constant 条目同样受概率约束，同 ST）
        return e.probability >= 100 || kotlin.random.Random.nextDouble(100.0) <= e.probability
    }

    /** 对齐 ST WorldInfoBuffer.matchKeys：正则 key 优先，普通 key 按（可覆盖的）大小写/全词设置匹配 */
    private fun matchKey(haystack: String, rawKey: String, e: WorldBookEntry, charName: String, userName: String): Boolean {
        // ST 在匹配前对 key 做宏替换
        val key = applyMacros(rawKey, charName, userName).trim()
        if (key.isEmpty()) return false
        parseRegexKey(key)?.let { return it.containsMatchIn(haystack) }
        val cs = e.caseSensitive ?: false
        val h = if (cs) haystack else haystack.lowercase()
        val n = if (cs) key else key.lowercase()
        return if (e.matchWholeWords == true) {
            // 多词短语退化为包含匹配；单词用含标点的自定义边界（同 ST）
            if (n.split(Regex("\\s+")).size > 1) h.contains(n)
            else Regex("(?:^|\\W)(${Regex.escape(n)})(?:$|\\W)").containsMatchIn(h)
        } else {
            h.contains(n)
        }
    }

    /** 解析 ST 的 `/pattern/flags` 正则 key（对齐 parseRegexFromString：含未转义 `/` 视为普通串） */
    private fun parseRegexKey(input: String): Regex? {
        val m = Regex("^/([\\w\\W]+?)/([gimsuy]*)$").find(input) ?: return null
        val (pattern, flags) = m.destructured
        if (Regex("(^|[^\\\\])/").containsMatchIn(pattern)) return null
        val opts = mutableSetOf<RegexOption>()
        if ('i' in flags) opts += RegexOption.IGNORE_CASE
        if ('m' in flags) opts += RegexOption.MULTILINE
        if ('s' in flags) opts += RegexOption.DOT_MATCHES_ALL
        return try { Regex(pattern, opts) } catch (_: Exception) { null }
    }

    /** 角色卡内嵌条目的 position 可能是字符串或数字串，归一化到与世界书一致 */
    private fun normalizeCardPosition(raw: String): String = when (raw) {
        "before_char", "0" -> "before_char"
        "at_depth", "4" -> "at_depth"
        else -> "after_char"
    }

    // ── 附件 ────────────────────────────────────────────────

    private fun loadImage(baseDir: File, relPath: String): ApiImage? = try {
        val f = File(baseDir, relPath)
        if (!f.exists()) null else ApiImage(
            mimeType = when (f.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            },
            base64 = Base64.getEncoder().encodeToString(f.readBytes()),
        )
    } catch (_: Exception) {
        null
    }

    /** 读取文件附件为文本；疑似二进制（开头含 NUL）返回 null，超长截断 */
    private fun readFileText(f: File): String? = try {
        if (!f.exists()) null else {
            val bytes = f.readBytes()
            val probe = bytes.take(8000)
            if (probe.contains(0.toByte())) null
            else {
                val text = String(bytes, Charsets.UTF_8)
                if (text.length > FILE_TEXT_MAX_CHARS) text.take(FILE_TEXT_MAX_CHARS) + "\n…(truncated)"
                else text
            }
        }
    } catch (_: Exception) {
        null
    }
}
