package me.rerere.fawntavern.domain

import me.rerere.fawntavern.data.api.GenParams
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.preset.PromptItem
import me.rerere.fawntavern.data.preset.StPreset
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.data.worldbook.WorldBookEntry
import me.rerere.fawntavern.data.worldbook.WorldBookPos
import me.rerere.fawntavern.data.worldbook.WorldInfoSettings

/**
 * Prompt 拼装。两步走：
 * 1. [build] —— 由角色卡 + 已加载的世界书/预设产出 [Built]（历史前后的提示块、
 *    深度注入块、发送侧正则、采样参数）。世界书条目按 constant/关键词扫描激活；
 *    预设存在时按 promptOrder 编排（marker 映射角色卡字段），否则用默认顺序。
 * 2. [PromptMessageAssembler.assemble] —— 生成请求前把 [Built] 与聊天历史合成完整消息数组：
 *    历史逐条套发送侧正则、文件附件内联为文本块、图片编码 base64、深度注入按位插入。
 * UI 与 ViewModel 不感知拼装细节。
 */
internal object PromptBuilder {

    /** 提示块来源标签（仅供 Prompt 日志展示，不影响拼装结果） */
    enum class PromptSource {
        CARD_DEFINITION,    // 默认拼装的角色定义合并块
        CARD_DESCRIPTION, CARD_PERSONALITY, CARD_SCENARIO, CARD_EXAMPLE,
        CARD_POST_HISTORY,  // 历史后指令
        CARD_NOTE,          // 角色注入提示（Character's Note）
        WORLD_INFO,         // 世界书条目
        PRESET,             // 预设条目
        USER_PERSONA,       // 用户人设描述
        EXTENSION,          // 扩展注入（摘要等）
        WEB_SEARCH,         // 联网搜索结果
        OTHER,
    }

    /** 历史之外的一个提示块。source/detail 仅供日志展示（detail = 世界书条目名/预设条目名等） */
    data class Piece(
        val role: String, val content: String,
        val source: PromptSource = PromptSource.OTHER, val detail: String = "",
    )

    /** 注入进聊天历史内部的提示块：depth = 0 紧跟最后一条消息，k = 倒数第 k 条之前 */
    data class DepthPiece(
        val role: String, val content: String, val depth: Int,
        val source: PromptSource = PromptSource.OTHER, val detail: String = "",
    )

    /** [build] 的产物，生成期间不变；每次请求由 [assemble] 与最新历史合成消息数组 */
    data class Built(
        val preHistory: List<Piece> = emptyList(),
        val postHistory: List<Piece> = emptyList(),
        val depthInjections: List<DepthPiece> = emptyList(),
        val promptRegex: List<CharRegex> = emptyList(),
        val genParams: GenParams? = null,
        val userName: String = "",
        val charName: String = "",
        val presetName: String = "",   // 关联预设名（空 = 无预设，走默认拼装）；仅供日志展示
        val maxContext: Int = 0,   // 上下文 token 上限（0 = 不裁剪，无预设时）
        val maxTokens: Int = 0,    // 为回复预留的 token
        val timedWi: Map<String, Int> = emptyMap(),  // 世界书定时效果状态（entryKey → 激活时的消息数），随会话持久化
        val macroContext: MacroContext = MacroContext(),
    )

    fun build(
        card: CharacterCard?,
        userName: String,
        userDescription: String = "",
        worldBooks: List<WorldBook> = emptyList(),
        preset: StPreset? = null,
        history: List<ChatMessage> = emptyList(),
        promptRegex: List<CharRegex> = emptyList(),
        timedWi: Map<String, Int> = emptyMap(),
        updateTimed: Boolean = true,
        wiSettings: WorldInfoSettings = WorldInfoSettings(),
        reasoning: ReasoningLevel = ReasoningLevel.AUTO,
        extraPre: List<Piece> = emptyList(),
        extraPost: List<Piece> = emptyList(),
        extraDepth: List<DepthPiece> = emptyList(),
        sessionId: String = "",
        input: String = "",
        model: String = "",
        summary: String = "",
        enabledExtensions: Set<String> = emptySet(),
        pickSalt: Int = 0,
        variableState: MacroVariableState = MacroVariableState(),
        allowVariableMutations: Boolean = false,
    ): Built {
        val charName = card?.name ?: ""
        val maxContext = preset?.maxContext?.takeIf { it > 0 } ?: 0
        val baseMacroContext = MacroContext(
            charName = charName,
            userName = userName,
            card = card,
            persona = userDescription,
            history = history,
            input = input,
            model = model,
            maxContextTokens = maxContext,
            maxResponseTokens = preset?.maxTokens?.takeIf { it > 0 } ?: 0,
            summary = summary,
            enabledExtensions = enabledExtensions,
            sessionId = sessionId,
            pickSalt = pickSalt,
            variables = variableState,
        )
        val macroPolicy = if (allowVariableMutations) MacroRenderPolicy.COMMIT_VARIABLES else MacroRenderPolicy.ALL
        val (wi, newTimed) = activateWorldInfo(
            card, worldBooks, history, userName, timedWi, updateTimed, wiSettings, maxContext,
            baseMacroContext,
        )
        fun bucket(pos: String) = wi.filter { it.position == pos }
            .map { Piece("system", it.content, PromptSource.WORLD_INFO, it.comment) }
        val wiBefore = bucket(WorldBookPos.BEFORE_CHAR)
        val wiAfter = bucket(WorldBookPos.AFTER_CHAR)
        val wiEmTop = bucket(WorldBookPos.EM_TOP)
        val wiEmBottom = bucket(WorldBookPos.EM_BOTTOM)
        // @D 深度注入：按条目 role 映射角色（0/1/2 → system/user/assistant）
        val wiDepth = wi.filter { it.position == WorldBookPos.AT_DEPTH }
            .map { DepthPiece(roleName(it.role), it.content, it.depth, PromptSource.WORLD_INFO, it.comment) }
        // 作者注释位置：本 App 无独立作者注释，映射为默认深度的 system 深度注入（an_top 先于 an_bottom）
        val wiAn = (wi.filter { it.position == WorldBookPos.AN_TOP } +
                    wi.filter { it.position == WorldBookPos.AN_BOTTOM })
            .map { DepthPiece("system", it.content, AN_DEPTH, PromptSource.WORLD_INFO, it.comment) }
        // outlet：不自动注入，只填充 {{outlet::名字}} 宏取用的映射（正文先过宏）
        val outletMap = wi.filter { it.position == WorldBookPos.OUTLET && it.outletName.isNotBlank() }
            .associate { it.outletName.trim() to MacroEngine.render(it.content, baseMacroContext, macroPolicy).trim() }
        val macroContext = baseMacroContext.copy(outlets = outletMap)

        val orderToggles = preset?.promptOrder
            ?.let { po -> po.firstOrNull { it.characterId == 100001 } ?: po.firstOrNull() }
            ?.order
        val assembled = if (preset != null && !orderToggles.isNullOrEmpty()) {
            buildWithPreset(preset, orderToggles, card, wiBefore, wiAfter, wiEmTop, wiEmBottom, userDescription)
        } else {
            buildDefault(card, wiBefore, wiAfter, wiEmTop, wiEmBottom)
        }

        // 采样参数来自预设；无预设时只有思考预算被显式调过才需要构造（否则保持不下发任何参数）
        val genParams = when {
            preset != null -> GenParams(
                temperature = preset.temperature,
                topP = preset.topP,
                topK = preset.topK.takeIf { k -> k > 0 },
                maxTokens = preset.maxTokens.takeIf { t -> t > 0 },
                frequencyPenalty = preset.frequencyPenalty.takeIf { v -> v != 0f },
                presencePenalty = preset.presencePenalty.takeIf { v -> v != 0f },
                seed = preset.seed.takeIf { s -> s >= 0 },
                reasoning = reasoning,
            )
            reasoning != ReasoningLevel.AUTO -> GenParams(reasoning = reasoning)
            else -> null
        }

        fun finalize(text: String, salt: Int = 0) =
            MacroEngine.render(text, macroContext.copy(pickSalt = pickSalt * 31 + salt), macroPolicy).trim()
        fun macro(pieces: List<Piece>) = pieces
            .mapIndexed { index, piece -> piece.copy(content = finalize(piece.content, index)) }
            .filter { it.content.isNotBlank() }

        // 角色注入提示（Character's Note）：按其深度并入深度注入
        val cardDepth = card?.depthPrompt?.takeIf { it.prompt.isNotBlank() }?.let {
            DepthPiece(it.role.ifBlank { "system" }, it.prompt, it.depth, PromptSource.CARD_NOTE)
        }

        return Built(
            preHistory = macro(assembled.pre + extraPre),
            postHistory = macro(assembled.post + extraPost),
            depthInjections = (wiDepth + wiAn + assembled.injections + listOfNotNull(cardDepth))
                .plus(extraDepth)
                .map { it.copy(content = finalize(it.content)) }
                .filter { it.content.isNotBlank() },
            promptRegex = promptRegex,
            genParams = genParams,
            userName = userName,
            charName = charName,
            presetName = preset?.name ?: "",
            maxContext = maxContext,
            maxTokens = preset?.maxTokens?.takeIf { it > 0 } ?: 0,
            timedWi = newTimed,
            macroContext = macroContext,
        )
    }

    /** @D 角色码 → API role 名（0=System 1=User 2=Assistant） */
    private fun roleName(role: Int): String = when (role) {
        1 -> "user"
        2 -> "assistant"
        else -> "system"
    }

    /** 作者注释缺省深度（本 App 无独立作者注释，an_top/an_bottom 映射到此深度） */
    private const val AN_DEPTH = 4

    // ── 拼装主体 ────────────────────────────────────────────

    private data class Assembled(
        val pre: List<Piece>,
        val post: List<Piece>,
        val injections: List<DepthPiece> = emptyList(),
    )

    /** 无预设：角色卡字段按固定顺序拼成单个 system 块（世界书插在角色定义前后 / 示例前后） */
    private fun buildDefault(
        card: CharacterCard?, wiBefore: List<Piece>, wiAfter: List<Piece>,
        wiEmTop: List<Piece>, wiEmBottom: List<Piece>,
    ): Assembled {
        card ?: return Assembled(emptyList(), emptyList())
        val parts = mutableListOf<String>()
        if (card.systemPrompt.isNotBlank()) parts += card.systemPrompt
        parts += wiBefore.map { it.content }
        if (card.description.isNotBlank()) parts += card.description
        if (card.personality.isNotBlank()) parts += "Personality: ${card.personality}"
        if (card.scenario.isNotBlank()) parts += "Scenario: ${card.scenario}"
        parts += wiAfter.map { it.content }
        // 示例消息前/后的世界书条目包裹示例块（示例为空时仍注入）
        parts += wiEmTop.map { it.content }
        if (card.mesExample.isNotBlank()) parts += "Example dialogue:\n${card.mesExample}"
        parts += wiEmBottom.map { it.content }
        val pre = if (parts.isEmpty()) emptyList()
            else listOf(Piece("system", parts.joinToString("\n\n"), PromptSource.CARD_DEFINITION))
        val post = card.postHistoryInstructions.takeIf { it.isNotBlank() }
            ?.let { listOf(Piece("system", it, PromptSource.CARD_POST_HISTORY)) } ?: emptyList()
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
        order: List<me.rerere.fawntavern.data.preset.PromptToggle>,
        card: CharacterCard?,
        wiBefore: List<Piece>,
        wiAfter: List<Piece>,
        wiEmTop: List<Piece>,
        wiEmBottom: List<Piece>,
        userDescription: String = "",
    ): Assembled {
        val prompts = preset.prompts.associateBy { it.identifier }
        val pre = mutableListOf<Piece>()
        val post = mutableListOf<Piece>()
        val injections = mutableListOf<DepthPiece>()
        var afterChat = false
        var emEmitted = false
        for (t in order) {
            if (!t.enabled) continue
            val p = prompts[t.identifier] ?: continue
            val target = if (afterChat) post else pre
            if (p.marker) {
                when (p.identifier) {
                    "chatHistory" -> afterChat = true
                    "charDescription" -> card?.description?.takeIf { it.isNotBlank() }?.let { target += Piece("system", it, PromptSource.CARD_DESCRIPTION) }
                    "charPersonality" -> card?.personality?.takeIf { it.isNotBlank() }?.let { target += Piece("system", it, PromptSource.CARD_PERSONALITY) }
                    "scenario" -> card?.scenario?.takeIf { it.isNotBlank() }?.let { target += Piece("system", it, PromptSource.CARD_SCENARIO) }
                    "dialogueExamples" -> {
                        wiEmTop.forEach { target += it }
                        card?.mesExample?.takeIf { it.isNotBlank() }?.let { target += Piece("system", it, PromptSource.CARD_EXAMPLE) }
                        wiEmBottom.forEach { target += it }
                        emEmitted = true
                    }
                    "worldInfoBefore" -> wiBefore.forEach { target += it }
                    "worldInfoAfter" -> wiAfter.forEach { target += it }
                    "personaDescription" -> userDescription.takeIf { it.isNotBlank() }?.let { target += Piece("system", it, PromptSource.USER_PERSONA) }
                    // 未知 marker 跳过
                }
                continue
            }
            if (!p.enabled) continue
            val content = overrideContent(p, card)
            if (content.isBlank()) continue
            val name = p.name.ifBlank { p.identifier }
            if (p.injectionPosition == 1) {
                injections += DepthPiece(p.role.ifBlank { "system" }, content, p.injectionDepth, PromptSource.PRESET, name)
            } else {
                target += Piece(p.role.ifBlank { "system" }, content, PromptSource.PRESET, name)
            }
        }
        // 预设未启用示例标记时，EM 位置条目兜底注入到历史前
        if (!emEmitted) (wiEmTop + wiEmBottom).forEach { pre += it }
        return Assembled(pre, post, injections)
    }

    private fun overrideContent(p: PromptItem, card: CharacterCard?): String {
        if (p.forbidOverrides) return p.content
        val override = when (p.identifier) {
            "main" -> card?.systemPrompt
            "jailbreak" -> card?.postHistoryInstructions
            else -> null
        }
        if (override.isNullOrBlank()) return p.content
        // {{original}} 引用被覆盖的预设原文（对齐 ST preparePrompt 的第二参 → {{original}} 宏）
        return override.replace("{{original}}", p.content, ignoreCase = true)
    }

    // ── 世界书激活 ──────────────────────────────────────────

    /** 递归封顶轮数（对应 ST world_info_max_recursion_steps，这里取一个安全上限） */
    private const val WI_MAX_ROUNDS = 25

    /** 定时效果状态的稳定键：id + 内容哈希（内容不变则跨轮稳定） */
    private fun entryKey(e: WorldBookEntry): String = "${e.id}:${e.content.hashCode()}"

    private fun stickyActive(a: Int?, sticky: Int, m: Int): Boolean =
        a != null && sticky > 0 && (m - a) < sticky

    private fun cooldownActive(a: Int?, sticky: Int, cooldown: Int, m: Int): Boolean =
        a != null && cooldown > 0 && (m - a) >= sticky && (m - a) < sticky + cooldown

    /**
     * 收集激活的世界书条目（对齐 ST checkWorldInfo 的高价值子集）。全局行为由 [settings] 控制
     * （扫描深度、递归开关/轮数、include_names、大小写/整词/群组评分默认、token 预算、最小激活）：
     * constant 始终激活；其余按关键词扫描最近 N 条消息（条目 scanDepth 覆盖 settings.scanDepth），
     * key 支持 `/pattern/flags` 正则，主命中后按 selectiveLogic 校验次级关键词。叠加：
     * **递归激活**（settings.recursive；excludeRecursion/preventRecursion/delayUntilRecursion）、
     * **inclusion group 互斥组**、**定时效果**（delay/cooldown/sticky）、
     * **最小激活**（不足则逐步加深全局扫描深度到 minActivationsDepthMax）、
     * **token 预算**（budgetPercent×maxContext，再被 budgetCap 封顶；超额丢弃靠后条目）。
     * 向量化条目需 embedding 后端做语义检索，当前不参与激活（仅保留数据）。
     * probability < 100 时掷骰（sticky 保持免掷）。结果按 insertionOrder 升序。
     *
     * @param timedWi 上一轮的定时状态（entryKey → 激活时消息数）
     * @param updateTimed 是否回写定时状态（重答历史消息时为 false，避免污染时效窗口）
     * @param maxContext 上下文 token 上限（预算按百分比计算用，0 = 无预设时不按百分比限制）
     * @return 激活条目（已排序）与更新后的定时状态
     */
    private fun activateWorldInfo(
        card: CharacterCard?,
        worldBooks: List<WorldBook>,
        history: List<ChatMessage>,
        userName: String,
        timedWi: Map<String, Int>,
        updateTimed: Boolean,
        settings: WorldInfoSettings,
        maxContext: Int,
        macroContext: MacroContext,
    ): Pair<List<WorldBookEntry>, Map<String, Int>> {
        // 只认关联的世界书。卡内 character_book 是导入载荷、不参与激活（同 ST：checkEmbeddedWorld
        // 仅据它显示导入按钮，生效与否只看链接），否则编辑/删除条目后旧内容还会从卡内那份注入。
        // 角色卡导入时内嵌书已抽成独立文件，并以不可变资源 ID 关联。
        // 向量化条目排除出激活候选（无 embedding 后端做语义检索，仅保留数据/状态）
        val candidates = worldBooks.flatMap { it.entries.values }
            .filter { it.enabled && it.content.isNotBlank() && !it.vectorized }
        val charName = card?.name ?: ""
        // include_names：扫描文本按 "名字: 内容" 前缀（同 ST chatForWI）
        val messages = history.filter { it.content.isNotBlank() }.map { msg ->
            if (settings.includeNames) {
                val nm = if (msg.role == "assistant") charName else userName
                if (nm.isNotBlank()) "$nm: ${msg.content}" else msg.content
            } else msg.content
        }
        val m = messages.size
        val maxRounds = when {
            !settings.recursive -> 1
            settings.maxRecursionSteps > 0 -> minOf(settings.maxRecursionSteps + 1, WI_MAX_ROUNDS)
            else -> WI_MAX_ROUNDS
        }

        // 一次完整（含递归）激活，全局扫描深度为 gScanDepth
        fun runActivation(gScanDepth: Int): LinkedHashMap<String, WorldBookEntry> {
            val activated = LinkedHashMap<String, WorldBookEntry>()
            val recurse = StringBuilder()
            var round = 0
            while (round < maxRounds) {
                val newThisRound = ArrayList<WorldBookEntry>()
                for (e in candidates) {
                    val key = entryKey(e)
                    if (key in activated) continue
                    if (shouldActivate(e, messages, recurse.toString(), round, m, timedWi,
                            charName, userName, gScanDepth, settings, macroContext)) {
                        activated[key] = e
                        newThisRound += e
                    }
                }
                if (newThisRound.isEmpty()) break
                val addable = newThisRound.filter { !it.preventRecursion }
                if (addable.isEmpty()) break
                addable.forEach { recurse.append('\n').append(it.content) }
                round++
            }
            return activated
        }

        // 最小激活：激活数不足则逐步加深全局扫描深度重扫，直到达标或触及上限
        var scanDepth = settings.scanDepth
        var activatedMap = runActivation(scanDepth)
        if (settings.minActivations > 0) {
            val depthCap = if (settings.minActivationsDepthMax > 0) settings.minActivationsDepthMax else m
            while (activatedMap.size < settings.minActivations && scanDepth < depthCap) {
                scanDepth++
                activatedMap = runActivation(scanDepth)
            }
        }

        var result = filterInclusionGroups(
            activatedMap.values.toList(), messages, scanDepth, charName, userName, settings, macroContext,
        )
        // 概率掷骰：sticky 保持期免掷
        result = result.filter { e ->
            val sticky = stickyActive(timedWi[entryKey(e)], e.sticky, m)
            sticky || e.probability >= 100 || kotlin.random.Random.nextDouble(100.0) <= e.probability
        }
        // 按 insertionOrder 降序（同 ST：同位置内 order 高者在前），再按 token 预算从前往后保留（超额丢弃靠后条目）
        var sorted = result.sortedByDescending { it.insertionOrder }
        val budget = wiBudget(settings, maxContext)
        if (budget > 0) {
            var used = 0
            val kept = ArrayList<WorldBookEntry>()
            for (e in sorted) {
                val cost = TokenEstimator.estimate(e.content) + 4
                if (kept.isNotEmpty() && used + cost > budget) break
                used += cost
                kept.add(e)
            }
            sorted = kept
        }

        // 更新定时状态（基于最终纳入的条目）
        val newTimed = if (!updateTimed) timedWi else {
            val map = timedWi.toMutableMap()
            val activeKeys = sorted.mapTo(HashSet()) { entryKey(it) }
            for (e in sorted) {
                if (e.sticky <= 0 && e.cooldown <= 0) continue
                val key = entryKey(e)
                val a = timedWi[key]
                // 新鲜激活（非 sticky 续期）才刷新起点，避免 sticky 窗口无限延长
                if (a == null || !stickyActive(a, e.sticky, m)) map[key] = m
            }
            // 清理已过期且本轮未激活的键，避免无限增长
            for (e in candidates) {
                val key = entryKey(e)
                val a = map[key] ?: continue
                if (key !in activeKeys && (m - a) >= e.sticky + e.cooldown) map.remove(key)
            }
            map
        }

        return sorted to newTimed
    }

    /** token 预算：budgetPercent×maxContext，再被 budgetCap 封顶；两者皆 ≤0 则不限（返回 0） */
    private fun wiBudget(settings: WorldInfoSettings, maxContext: Int): Int {
        val pct = if (maxContext > 0) (maxContext.toLong() * settings.budgetPercent / 100).toInt() else 0
        val cap = settings.budgetCap
        return when {
            pct > 0 && cap > 0 -> minOf(pct, cap)
            pct > 0 -> pct
            cap > 0 -> cap
            else -> 0
        }
    }

    /** 单个条目在本轮是否激活（关键词 / constant / 定时效果综合判定） */
    private fun shouldActivate(
        e: WorldBookEntry, messages: List<String>, recurseText: String, round: Int,
        m: Int, timedWi: Map<String, Int>, charName: String, userName: String,
        globalScanDepth: Int, settings: WorldInfoSettings, macroContext: MacroContext,
    ): Boolean {
        if (e.delay > 0 && m < e.delay) return false          // 聊天不足 N 条前不激活
        val a = timedWi[entryKey(e)]
        if (stickyActive(a, e.sticky, m)) return true          // 保持期强制激活
        if (cooldownActive(a, e.sticky, e.cooldown, m)) return false  // 冷却期抑制
        if (e.delayUntilRecursion && round == 0) return false  // 仅递归轮
        if (e.constant) return true
        if (e.keys.isEmpty()) return false
        // excludeRecursion 只匹配真实消息，不看递归缓冲
        val depth = e.scanDepth ?: globalScanDepth
        val realScan = messages.takeLast(depth).joinToString("\n")
        val haystack = if (e.excludeRecursion || recurseText.isBlank()) realScan else realScan + "\n" + recurseText
        return keywordMatch(e, haystack, charName, userName, settings, macroContext)
    }

    /** 主关键词 + selectiveLogic 次级关键词判定 */
    private fun keywordMatch(
        e: WorldBookEntry, haystack: String, charName: String, userName: String,
        settings: WorldInfoSettings, macroContext: MacroContext,
    ): Boolean {
        val primary = e.keys.any { matchKey(haystack, it, e, charName, userName, settings, macroContext) }
        if (!primary) return false
        if (e.keySecondary.isEmpty()) return true
        val matches = e.keySecondary.map { matchKey(haystack, it, e, charName, userName, settings, macroContext) }
        return when (e.selectiveLogic) {
            1 -> !matches.all { it }   // NOT_ALL
            2 -> matches.none { it }   // NOT_ANY
            3 -> matches.all { it }    // AND_ALL
            else -> matches.any { it } // AND_ANY
        }
    }

    /** 关键词命中计数（inclusion group 评分用） */
    private fun keywordScore(
        e: WorldBookEntry, messages: List<String>, globalScanDepth: Int, charName: String,
        userName: String, settings: WorldInfoSettings, macroContext: MacroContext,
    ): Int {
        val depth = e.scanDepth ?: globalScanDepth
        val scan = messages.takeLast(depth).joinToString("\n")
        return (e.keys + e.keySecondary).count {
            matchKey(scan, it, e, charName, userName, settings, macroContext)
        }
    }

    /**
     * inclusion group 过滤：把激活条目按组名（逗号分隔可多组）分桶，每组只留一个胜者，
     * 其余从结果中剔除。胜者：groupOverride 优先 → useGroupScoring（条目或全局）按命中数 → 否则 groupWeight 加权随机。
     */
    private fun filterInclusionGroups(
        entries: List<WorldBookEntry>, messages: List<String>, globalScanDepth: Int,
        charName: String, userName: String, settings: WorldInfoSettings, macroContext: MacroContext,
    ): List<WorldBookEntry> {
        val grouped = entries.filter { it.group.isNotBlank() }
        if (grouped.isEmpty()) return entries
        val losers = HashSet<String>()  // entryKey
        val byGroup = LinkedHashMap<String, MutableList<WorldBookEntry>>()
        for (e in grouped) {
            e.group.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { g ->
                byGroup.getOrPut(g) { mutableListOf() }.add(e)
            }
        }
        for ((_, members) in byGroup) {
            if (members.size < 2) continue
            val overrides = members.filter { it.groupOverride }
            val pool = overrides.ifEmpty { members }
            val winner = when {
                settings.useGroupScoring || pool.any { it.useGroupScoring } ->
                    pool.maxByOrNull {
                        keywordScore(it, messages, globalScanDepth, charName, userName, settings, macroContext)
                    }!!
                else -> weightedPick(pool)
            }
            members.forEach { if (entryKey(it) != entryKey(winner)) losers.add(entryKey(it)) }
        }
        return entries.filter { entryKey(it) !in losers }
    }

    /** 按 groupWeight 加权随机选一个 */
    private fun weightedPick(pool: List<WorldBookEntry>): WorldBookEntry {
        val total = pool.sumOf { it.groupWeight.coerceAtLeast(1) }
        var r = kotlin.random.Random.nextInt(total)
        for (e in pool) {
            r -= e.groupWeight.coerceAtLeast(1)
            if (r < 0) return e
        }
        return pool.last()
    }

    /** 对齐 ST WorldInfoBuffer.matchKeys：正则 key 优先，普通 key 按（条目覆盖 or 全局默认的）大小写/全词设置匹配 */
    private fun matchKey(
        haystack: String, rawKey: String, e: WorldBookEntry, charName: String, userName: String,
        settings: WorldInfoSettings, macroContext: MacroContext,
    ): Boolean {
        // ST 在匹配前对 key 做宏替换
        val key = MacroEngine.render(rawKey, macroContext).trim()
        if (key.isEmpty()) return false
        parseRegexKey(key)?.let { return it.containsMatchIn(haystack) }
        val cs = e.caseSensitive ?: settings.caseSensitive
        val h = if (cs) haystack else haystack.lowercase()
        val n = if (cs) key else key.lowercase()
        return if (e.matchWholeWords ?: settings.matchWholeWords) {
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

}
