package me.rerere.stapp.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * SillyTavern 风格的 `{{宏}}` 替换（对齐 substituteParams 的常用子集）。
 *
 * 支持：`{{char}}`/`{{user}}`/`{{newline}}`、时间日期 `{{time}}`/`{{date}}`/`{{weekday}}`、
 * 随机 `{{roll:N}}`/`{{random:a,b,c}}`/`{{pick:a,b,c}}`。分隔符逗号或 `::`（后者允许项内含逗号）。
 * `{{pick}}` 按参数内容做稳定选择（同参数每次同结果，避免重答时抖动），`{{random}}` 每次现掷。
 */
internal object Macros {

    fun apply(text: String, charName: String, userName: String): String {
        if (text.isEmpty() || '{' !in text) return text
        var t = text
            .replace("{{char}}", charName, ignoreCase = true)
            .replace("{{user}}", userName, ignoreCase = true)
            .replace("{{newline}}", "\n", ignoreCase = true)
        if ("{{" !in t) return t
        t = applyDateTime(t)
        t = applyRandom(t)
        return t
    }

    private val ROLL = Regex("\\{\\{roll[:：]\\s*[dD]?(\\d+)\\s*\\}\\}", RegexOption.IGNORE_CASE)
    private val RANDOM = Regex("\\{\\{random[:：](.*?)\\}\\}", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val PICK = Regex("\\{\\{pick[:：](.*?)\\}\\}", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private fun applyRandom(text: String): String {
        var t = ROLL.replace(text) { m ->
            val n = m.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: return@replace m.value
            (Random.nextInt(n) + 1).toString()
        }
        t = RANDOM.replace(t) { m ->
            val items = splitArgs(m.groupValues[1])
            if (items.isEmpty()) "" else items[Random.nextInt(items.size)]
        }
        t = PICK.replace(t) { m ->
            val raw = m.groupValues[1]
            val items = splitArgs(raw)
            if (items.isEmpty()) "" else items[(raw.hashCode().toLong() and 0x7fffffffL).toInt() % items.size]
        }
        return t
    }

    /** 逗号或 `::` 分隔；出现 `::` 时优先按它拆（允许项内含逗号）。 */
    private fun splitArgs(raw: String): List<String> {
        val parts = if (raw.contains("::")) raw.split("::") else raw.split(",")
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun applyDateTime(text: String): String {
        if (!text.contains("{{time", ignoreCase = true) &&
            !text.contains("{{date", ignoreCase = true) &&
            !text.contains("{{weekday", ignoreCase = true)
        ) return text
        val now = Date()
        fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault()).format(now)
        return text
            .replace("{{time}}", fmt("HH:mm"), ignoreCase = true)
            .replace("{{date}}", fmt("yyyy-MM-dd"), ignoreCase = true)
            .replace("{{weekday}}", fmt("EEEE"), ignoreCase = true)
    }
}
