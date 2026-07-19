package me.rerere.stapp.data.settings

import android.content.Context
import me.rerere.stapp.data.worldbook.WorldInfoSettings

/** 全局世界书激活设置 — SharedPreferences 持久化（扁平键，默认值取自 [WorldInfoSettings]） */
object WorldInfoSettingsStore {
    private const val PREFS = "world_info_settings"

    private const val K_SCAN_DEPTH = "scanDepth"
    private const val K_BUDGET_PCT = "budgetPercent"
    private const val K_BUDGET_CAP = "budgetCap"
    private const val K_MIN_ACT = "minActivations"
    private const val K_MIN_ACT_DEPTH = "minActivationsDepthMax"
    private const val K_MAX_RECURSION = "maxRecursionSteps"
    private const val K_INCLUDE_NAMES = "includeNames"
    private const val K_RECURSIVE = "recursive"
    private const val K_CASE_SENSITIVE = "caseSensitive"
    private const val K_WHOLE_WORDS = "matchWholeWords"
    private const val K_GROUP_SCORING = "useGroupScoring"
    private const val K_OVERFLOW_ALERT = "overflowAlert"

    fun get(context: Context): WorldInfoSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = WorldInfoSettings()
        return WorldInfoSettings(
            scanDepth = p.getInt(K_SCAN_DEPTH, d.scanDepth),
            budgetPercent = p.getInt(K_BUDGET_PCT, d.budgetPercent),
            budgetCap = p.getInt(K_BUDGET_CAP, d.budgetCap),
            minActivations = p.getInt(K_MIN_ACT, d.minActivations),
            minActivationsDepthMax = p.getInt(K_MIN_ACT_DEPTH, d.minActivationsDepthMax),
            maxRecursionSteps = p.getInt(K_MAX_RECURSION, d.maxRecursionSteps),
            includeNames = p.getBoolean(K_INCLUDE_NAMES, d.includeNames),
            recursive = p.getBoolean(K_RECURSIVE, d.recursive),
            caseSensitive = p.getBoolean(K_CASE_SENSITIVE, d.caseSensitive),
            matchWholeWords = p.getBoolean(K_WHOLE_WORDS, d.matchWholeWords),
            useGroupScoring = p.getBoolean(K_GROUP_SCORING, d.useGroupScoring),
            overflowAlert = p.getBoolean(K_OVERFLOW_ALERT, d.overflowAlert),
        )
    }

    fun set(context: Context, s: WorldInfoSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(K_SCAN_DEPTH, s.scanDepth.coerceAtLeast(0))
            .putInt(K_BUDGET_PCT, s.budgetPercent.coerceIn(0, 100))
            .putInt(K_BUDGET_CAP, s.budgetCap.coerceAtLeast(0))
            .putInt(K_MIN_ACT, s.minActivations.coerceAtLeast(0))
            .putInt(K_MIN_ACT_DEPTH, s.minActivationsDepthMax.coerceAtLeast(0))
            .putInt(K_MAX_RECURSION, s.maxRecursionSteps.coerceAtLeast(0))
            .putBoolean(K_INCLUDE_NAMES, s.includeNames)
            .putBoolean(K_RECURSIVE, s.recursive)
            .putBoolean(K_CASE_SENSITIVE, s.caseSensitive)
            .putBoolean(K_WHOLE_WORDS, s.matchWholeWords)
            .putBoolean(K_GROUP_SCORING, s.useGroupScoring)
            .putBoolean(K_OVERFLOW_ALERT, s.overflowAlert)
            .apply()
    }
}
