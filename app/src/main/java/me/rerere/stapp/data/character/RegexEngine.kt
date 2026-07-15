package me.rerere.stapp.data.character

/**
 * SillyTavern 显示用正则引擎。
 *
 * ST 的 findRegex 采用 JS 字面量语法 `/pattern/flags`，replaceString 用 `$0` `$1` 捕获组、
 * `{{match}}` 等宏。行为尽可能对齐 ST 的 StringTemplate + substituteParams。
 */
object RegexEngine {

    /** ST 合法 flag 字符集（含非标准 x X A U J）。 */
    private val KNOWN_FLAGS = "gimsuxXAUJy"

    /**
     * 对一条消息套用所有启用的显示用正则，然后做宏替换。
     *
     * @param depth  深度：0 = 最新消息，1 = 倒数第二条，null = 不限
     * @param userName persona 名（用于 {{user}}）
     * @param charName 角色名（用于 {{char}}）
     */
    fun applyForDisplay(
        content: String,
        scripts: List<CharRegex>,
        depth: Int? = null,
        userName: String = "",
        charName: String = "",
    ): String = apply(content, scripts, depth, placementTarget = 2, forPrompt = false,
        userName = userName, charName = charName)

    /**
     * 发送侧：构建 API 请求前对历史消息套用正则。
     * 与显示侧互补——promptOnly 的脚本只在这里生效，markdownOnly 的只在显示侧生效，
     * 两个标志都为 false 的脚本两侧都生效（ST 里它直接改写聊天记录，这里以双侧套用近似）。
     *
     * @param role 消息角色（"user"/"assistant"），决定 placement 过滤（1=用户消息 2=AI 消息）
     */
    fun applyForPrompt(
        content: String,
        scripts: List<CharRegex>,
        depth: Int?,
        role: String,
        userName: String = "",
        charName: String = "",
    ): String = apply(content, scripts, depth, placementTarget = if (role == "user") 1 else 2,
        forPrompt = true, userName = userName, charName = charName)

    private fun apply(
        content: String,
        scripts: List<CharRegex>,
        depth: Int?,
        placementTarget: Int,
        forPrompt: Boolean,
        userName: String,
        charName: String,
    ): String {
        if (content.isBlank()) return content
        var text = content
        for (s in scripts) {
            if (s.disabled) continue
            // 空 placement 视为不限
            if (s.placement.isNotEmpty() && placementTarget !in s.placement) continue
            if (forPrompt) {
                // markdownOnly 的正则只作用于显示，不改发送内容
                if (s.markdownOnly && !s.promptOnly) continue
            } else {
                // promptOnly 的正则只作用于发送内容，不用于显示
                if (s.promptOnly && !s.markdownOnly) continue
            }
            // 深度过滤：depth 从最后一条消息 0 向上递增
            if (depth != null) {
                if (s.minDepth != null && depth < s.minDepth) continue
                if (s.maxDepth != null && depth > s.maxDepth) continue
            }
            // substituteRegex：对 findRegex 做宏替换后再编译
            val findRaw = when (s.substituteRegex) {
                1 -> substituteMacros(s.findRegex, userName, charName, escape = false)
                2 -> substituteMacros(s.findRegex, userName, charName, escape = true)
                else -> s.findRegex
            }
            val compiled = compile(findRaw) ?: continue
            text = replace(text, compiled, s.replaceString, s.trimStrings)
        }
        // 最终宏替换：捕捉内容中可能残留的 {{user}}/{{char}}/{{newline}}
        return substituteMacros(text, userName, charName)
    }

    /**
     * 根据脚本集的深度限制计算稳定"深度键"，用于 Compose remember 避免新消息到达时重算旧消息。
     *
     * 返回的整数值只在深度跨越某个脚本的 [minDepth]/[maxDepth] 边界时才变化，
     * 同一深度桶内的消息正则输出相同，可用作记得键。
     */
    fun depthKey(scripts: List<CharRegex>, depth: Int?): Int {
        val d = depth ?: return -1
        val thresholds = mutableSetOf(0, Int.MAX_VALUE)
        for (s in scripts) {
            s.minDepth?.let { thresholds.add(it) }
            s.maxDepth?.let { thresholds.add(it + 1) } // maxDepth+1 = 脚本关闭的边界
        }
        val sorted = thresholds.sorted()
        val bucket = sorted.indexOfFirst { d < it }
        return if (bucket == -1) sorted.size else bucket
    }

    private data class Compiled(val regex: Regex, val global: Boolean)

    /**
     * 把 JS 正则字面量 `/pattern/flags` 编译为 Kotlin Regex。
     * - 通过 flag 验证：只有合法 flag 字符才视为 flags，否则整串视为纯 pattern。
     * - g 标记影响替换行为（全部 vs 首个）；u/y/d/v 等 Kotlin 不支持的标记静默忽略。
     */
    private fun compile(find: String): Compiled? {
        val raw = find.trim()
        if (raw.isEmpty()) return null
        var pattern = raw
        var flags = ""
        if (raw.length > 1 && raw.startsWith("/")) {
            val end = raw.lastIndexOf('/')
            if (end > 0) {
                val flagStr = raw.substring(end + 1)
                // 验证：所有字符必须是已知 flag，否则不以 flags 视之
                if (flagStr.all { it in KNOWN_FLAGS }) {
                    pattern = raw.substring(1, end)
                    flags = flagStr
                }
            }
        }
        val options = mutableSetOf<RegexOption>()
        if (flags.contains('i')) options.add(RegexOption.IGNORE_CASE)
        if (flags.contains('m')) options.add(RegexOption.MULTILINE)
        if (flags.contains('s')) options.add(RegexOption.DOT_MATCHES_ALL)
        if (flags.contains('x')) options.add(RegexOption.COMMENTS)
        val regex = try { Regex(pattern, options) } catch (_: Exception) { return null }
        return Compiled(regex, flags.contains('g'))
    }

    /**
     * 展开替换字符串：
     * 1. `$0` `$&` → 整体匹配，`$1`–`$99` → 捕获组（超过现有组数返回空串）
     * 2. `$$` → 字面 `$`，`{{match}}` → 整体匹配
     * 3. 每个捕获组的值应用 [trimStrings] 的字符串移除
     * 4. 替换完成后对整段结果做宏替换（{{user}} {{char}} {{newline}}）
     */
    private fun replace(text: String, compiled: Compiled, replaceStr: String, trimStrings: List<String>): String {
        val replacer: (MatchResult) -> String = { m -> expand(replaceStr, m, trimStrings) }
        return if (compiled.global) {
            compiled.regex.replace(text, replacer)
        } else {
            val m = compiled.regex.find(text) ?: return text
            val sb = StringBuilder(text.length + 64)
            sb.append(text, 0, m.range.first)
            sb.append(replacer(m))
            sb.append(text, m.range.last + 1, text.length)
            sb.toString()
        }
    }

    private fun expand(replace: String, m: MatchResult, trimStrings: List<String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < replace.length) {
            val c = replace[i]
            if (c == '$' && i + 1 < replace.length) {
                val n = replace[i + 1]
                when {
                    n == '$' -> { sb.append('$'); i += 2; continue }
                    n == '&' -> { sb.append(m.value); i += 2; continue }
                    n.isDigit() -> {
                        var j = i + 1
                        while (j < replace.length && replace[j].isDigit() && j - i <= 2) j++
                        val idx = replace.substring(i + 1, j).toInt()
                        var value = m.groupValues.getOrElse(idx) { "" }
                        // trimStrings 只作用在捕获组（idx > 0）上
                        if (trimStrings.isNotEmpty() && idx > 0) {
                            for (trim in trimStrings) value = value.replace(trim, "")
                        }
                        sb.append(value)
                        i = j; continue
                    }
                }
            }
            if (replace.startsWith("{{match}}", i)) { sb.append(m.value); i += 9; continue }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /**
     * 替换文本中的 `{{user}}` `{{char}}` `{{newline}}` 宏。
     * 当 [escape] = true 时，用户名/角色名用 [Regex.escape] 转义（用于 findRegex 中的 substituteRegex=2 模式）。
     */
    private fun substituteMacros(text: String, userName: String, charName: String, escape: Boolean = false): String {
        var t = text.replace("{{newline}}", "\n", ignoreCase = true)
        if (userName.isNotBlank()) {
            val v = if (escape) Regex.escape(userName) else userName
            t = t.replace("{{user}}", v, ignoreCase = true)
        }
        if (charName.isNotBlank()) {
            val v = if (escape) Regex.escape(charName) else charName
            t = t.replace("{{char}}", v, ignoreCase = true)
        }
        return t
    }
}
