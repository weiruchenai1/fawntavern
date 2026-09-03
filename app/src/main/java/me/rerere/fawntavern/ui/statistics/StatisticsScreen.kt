package me.rerere.fawntavern.ui.statistics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChartColumnBig
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Rocket
import com.composables.icons.lucide.Zap
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import me.rerere.fawntavern.R
import me.rerere.fawntavern.ui.components.AppTopBar

@Composable
fun StatisticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(StatisticsUiState()) }
    val controller = remember(context) { StatisticsController(AndroidStatisticsDataSource(context)) }

    LaunchedEffect(Unit) {
        state = controller.load()
    }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.statistics_title), onBack) },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { HeatmapPanel(state.messagesPerDay) }
                item {
                    StatRow(
                        icon = Lucide.ChartColumnBig,
                        label = stringResource(R.string.stats_total_conversations),
                        value = formatCount(state.totalConversations.toLong()),
                    )
                }
                item {
                    StatRow(
                        icon = Lucide.MessageCircle,
                        label = stringResource(R.string.stats_total_messages),
                        value = formatCount(state.totalMessages.toLong()),
                    )
                }
                item {
                    StatRow(
                        icon = Lucide.Cpu,
                        label = stringResource(R.string.stats_input_tokens),
                        value = formatTokens(state.promptTokens),
                    )
                }
                item {
                    StatRow(
                        icon = Lucide.Cpu,
                        label = stringResource(R.string.stats_output_tokens),
                        value = formatTokens(state.completionTokens),
                    )
                }
                item {
                    StatRow(
                        icon = Lucide.Zap,
                        label = stringResource(R.string.stats_cached_tokens),
                        value = formatTokens(state.cachedTokens),
                    )
                }
                item {
                    StatRow(
                        icon = Lucide.Rocket,
                        label = stringResource(R.string.stats_launch_count),
                        value = formatCount(state.launchCount.toLong()),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeatmapPanel(messagesPerDay: Map<LocalDate, Int>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.stats_heatmap), style = MaterialTheme.typography.titleMedium)
            ChatHeatmap(messagesPerDay)
        }
    }
}

@Composable
private fun ChatHeatmap(messagesPerDay: Map<LocalDate, Int>) {
    val today = remember { LocalDate.now() }
    val locale = LocalLocale.current.platformLocale
    val firstDay = today.minusDays(364)
    val startSunday = firstDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    val currentSunday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    val weekCount = ChronoUnit.WEEKS.between(startSunday, currentSunday).toInt() + 1
    val activeCounts = messagesPerDay.values.filter { it > 0 }.sorted()
    val q1 = activeCounts.getOrElse((activeCounts.size * 0.25).toInt()) { 1 }
    val q2 = activeCounts.getOrElse((activeCounts.size * 0.50).toInt()) { 2 }
    val q3 = activeCounts.getOrElse((activeCounts.size * 0.75).toInt()) { 3 }
    val cellSize = 12.dp
    val cellPadding = 2.dp
    val cellPitch = cellSize + cellPadding * 2
    val weekGap = 2.dp
    val monthLabelHeight = 16.dp
    val monthLabelGap = 6.dp
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)
    val dayLabels = listOf(
        "",
        stringResource(R.string.stats_dow_mon),
        "",
        stringResource(R.string.stats_dow_wed),
        "",
        stringResource(R.string.stats_dow_fri),
        "",
    )

    Column {
        Row {
            Column(modifier = Modifier.width(22.dp)) {
                Spacer(Modifier.height(monthLabelHeight + monthLabelGap))
                dayLabels.forEach { label ->
                    Box(
                        Modifier.width(22.dp).height(cellPitch),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (label.isNotEmpty()) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Column(Modifier.horizontalScroll(scrollState)) {
                Row(horizontalArrangement = Arrangement.spacedBy(weekGap)) {
                    repeat(weekCount) { weekIndex ->
                        val weekStart = startSunday.plusWeeks(weekIndex.toLong())
                        val visibleDays = ChronoUnit.DAYS.between(weekStart, today)
                            .toInt().plus(1).coerceIn(0, 7)
                        val labelDate = (0 until visibleDays)
                            .map { weekStart.plusDays(it.toLong()) }
                            .firstOrNull { it.dayOfMonth == 1 }
                        Box(
                            Modifier.width(cellPitch).height(monthLabelHeight),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (labelDate != null) {
                                Text(
                                    labelDate.month.getDisplayName(TextStyle.SHORT, locale),
                                    modifier = Modifier.wrapContentWidth(unbounded = true),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    softWrap = false,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(monthLabelGap))
                Row(horizontalArrangement = Arrangement.spacedBy(weekGap)) {
                    repeat(weekCount) { weekIndex ->
                        val weekStart = startSunday.plusWeeks(weekIndex.toLong())
                        val visibleDays = ChronoUnit.DAYS.between(weekStart, today)
                            .toInt().plus(1).coerceIn(0, 7)
                        Column {
                            repeat(visibleDays) { dayIndex ->
                                val date = weekStart.plusDays(dayIndex.toLong())
                                val count = messagesPerDay[date] ?: 0
                                val level = when {
                                    count == 0 -> 0
                                    count <= q1 -> 1
                                    count <= q2 -> 2
                                    count <= q3 -> 3
                                    else -> 4
                                }
                                Box(Modifier.padding(cellPadding)) {
                                    HeatmapCell(level, 12)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(22.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.stats_heatmap_less),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                repeat(5) { level ->
                    HeatmapCell(level, 10)
                    Spacer(Modifier.width(3.dp))
                }
                Spacer(Modifier.width(3.dp))
                Text(
                    stringResource(R.string.stats_heatmap_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HeatmapCell(level: Int, sizeDp: Int) {
    val alpha = when (level) {
        1 -> 0.35f
        2 -> 0.52f
        3 -> 0.68f
        4 -> 0.92f
        else -> 0f
    }
    val color = if (level == 0) {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    }
    Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape(3.dp)).background(color))
}

@Composable
private fun StatRow(icon: ImageVector, label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(Locale.US, count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(Locale.US, count / 1_000.0)
    else -> count.toString()
}

private fun formatTokens(count: Long): String = when {
    count >= 1_000_000_000 -> "%.2fB".format(Locale.US, count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.2fM".format(Locale.US, count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(Locale.US, count / 1_000.0)
    else -> count.toString()
}
