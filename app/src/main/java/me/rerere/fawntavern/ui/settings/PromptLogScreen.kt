package me.rerere.fawntavern.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import me.rerere.fawntavern.R
import me.rerere.fawntavern.domain.LoggedMessage
import me.rerere.fawntavern.domain.PromptLog
import me.rerere.fawntavern.domain.PromptLogEntry
import me.rerere.fawntavern.ui.components.AppTopBar
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16

/**
 * Prompt 调试日志页：开关记录 + 逐条查看每次生成最终组装出的完整 prompt。
 * 每条记录展开后逐条列出实际发送的消息数组，每条消息默认折叠、点击展开查看完整内容，
 * 便于核对角色卡 / 世界书 / 预设是否已正确插入组装。数据来自 [PromptLog]（纯内存，重启即清空）。
 */
@Composable
fun PromptLogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val controller = remember(ctx) { SettingsDataController(AndroidSettingsDataSource(ctx)) }
    BackHandler(onBack = onBack)

    var enabled by remember(controller) { mutableStateOf(controller.promptLogEnabled()) }
    val entries by PromptLog.entries.collectAsState()

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(stringResource(R.string.debug_log), onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── 开关 + 说明 ──
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = Space16, end = Space16, top = Space16, bottom = Space12),
                verticalArrangement = Arrangement.spacedBy(Space8),
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = Space16, vertical = Space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.debug_log_enable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            enabled = controller.setPromptLogEnabled(on)
                            PromptLog.enabled = on
                        },
                    )
                }
                Text(
                    stringResource(R.string.debug_log_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 条数 + 清空 ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.debug_log_entry_count, entries.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (entries.isNotEmpty()) {
                    TextButton(onClick = { PromptLog.clear() }) {
                        Text(stringResource(R.string.debug_log_clear))
                    }
                }
            }

            if (entries.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(Space16),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.debug_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Space16),
                    verticalArrangement = Arrangement.spacedBy(Space12),
                ) {
                    items(entries) { entry -> LogEntryCard(entry) }
                }
            }
        }
    }
}

@Composable
private fun LogEntryCard(entry: PromptLogEntry) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Space12),
        verticalArrangement = Arrangement.spacedBy(Space8),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatTime(entry.time),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val model = listOf(entry.providerName, entry.modelId)
                    .filter { it.isNotBlank() }.joinToString(" / ")
                if (model.isNotBlank()) {
                    Text(
                        model,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Lucide.ChevronDown, null,
                Modifier.size(20.dp).rotate(if (expanded) 180f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 摘要行：角色 / 预设 / 世界书条数 / 消息条数 / token 估算 / 思考档位（非 AUTO 才显示）
        val noPreset = stringResource(R.string.debug_log_no_preset)
        val wi = stringResource(R.string.debug_log_wi_count, entry.worldInfoCount)
        val msgs = stringResource(R.string.debug_log_msg_count, entry.messages.size)
        val tok = stringResource(R.string.debug_log_tokens, entry.approxTokens)
        val reasoning = entry.params?.reasoning
            ?.takeIf { it != me.rerere.fawntavern.data.api.ReasoningLevel.AUTO }
            ?.let { stringResource(R.string.debug_log_reasoning, it.name) }
        val summary = buildList {
            if (entry.charName.isNotBlank()) add(entry.charName)
            add(entry.presetName ?: noPreset)
            add(wi); add(msgs); add(tok)
            reasoning?.let { add(it) }
        }.joinToString("  ·  ")
        Text(
            summary,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionHeader(stringResource(R.string.debug_log_messages), entry.messages.size)
            entry.messages.forEach { MessageRow(it) }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        "$title ($count)",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun MessageRow(msg: LoggedMessage) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Space8),
        verticalArrangement = Arrangement.spacedBy(Space4),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space8),
        ) {
            SourceChip(msg.role)
            if (msg.imageCount > 0) {
                Text(
                    stringResource(R.string.debug_log_images, msg.imageCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                Lucide.ChevronDown, null,
                Modifier.size(18.dp).rotate(if (expanded) 180f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (msg.content.isNotBlank()) {
            if (expanded) {
                SelectionContainer {
                    Text(
                        msg.content,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Text(
                    msg.content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SourceChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = Space8, vertical = 2.dp),
    )
}

private fun formatTime(t: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(t))
