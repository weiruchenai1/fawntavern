package me.rerere.fawntavern.ui.statistics

import java.time.LocalDate
import java.time.ZoneId

data class StatisticsSnapshot(
    val totalConversations: Int,
    val totalMessages: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val launchCount: Int,
    val messagesPerDay: Map<String, Int>,
)

data class StatisticsUiState(
    val loading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val launchCount: Int = 0,
    val messagesPerDay: Map<LocalDate, Int> = emptyMap(),
)

fun interface StatisticsDataSource {
    suspend fun snapshot(sinceEpochMillis: Long): StatisticsSnapshot
}

class StatisticsController(
    private val dataSource: StatisticsDataSource,
) {
    suspend fun load(
        today: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): StatisticsUiState {
        val startMillis = today.minusDays(364).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val snapshot = dataSource.snapshot(startMillis)
        return StatisticsUiState(
            loading = false,
            totalConversations = snapshot.totalConversations,
            totalMessages = snapshot.totalMessages,
            promptTokens = snapshot.promptTokens,
            completionTokens = snapshot.completionTokens,
            cachedTokens = snapshot.cachedTokens,
            launchCount = snapshot.launchCount,
            messagesPerDay = snapshot.messagesPerDay.mapNotNull { (day, count) ->
                runCatching { LocalDate.parse(day) to count }.getOrNull()
            }.toMap(),
        )
    }
}
