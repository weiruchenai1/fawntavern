package me.rerere.stapp.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleChevronLeft
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.stapp.R
import me.rerere.stapp.data.chat.ChatRepository
import me.rerere.stapp.data.chat.ChatSession
import me.rerere.stapp.data.settings.SearchHistoryStore

private val Space8 = 8.dp
private val Space12 = 12.dp
private val Space16 = 16.dp

/** 一条消息命中结果 */
private data class SearchHit(
    val session: ChatSession,
    val snippet: String,
)

@Composable
fun SearchScreen(
    charFile: String = "",
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(SearchHistoryStore.getHistory(context)) }
    val focusRequester = remember { FocusRequester() }

    // 当前角色卡的会话（搜索范围）
    var sessions by remember { mutableStateOf<List<ChatSession>>(emptyList()) }
    LaunchedEffect(charFile) {
        sessions = withContext(Dispatchers.IO) {
            ChatRepository.list(context).filter { it.charFile == charFile }
        }
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // 与其他页面一致的页头：CircleChevronLeft + 居中标题
            Box(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    Lucide.CircleChevronLeft, stringResource(R.string.back),
                    Modifier.size(24.dp).clickable { onBack() },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.search_chats),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    ) { padding ->
        // 在当前角色卡的会话里搜消息内容，每个会话取首个命中片段
        val hits: List<SearchHit> = if (query.isBlank()) emptyList() else {
            val q = query.trim()
            sessions.mapNotNull { s ->
                val msg = s.messages.firstOrNull { it.content.contains(q, ignoreCase = true) }
                    ?: return@mapNotNull null
                SearchHit(s, buildSnippet(msg.content, q))
            }
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── 搜索输入框 ──
            Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space8),
        ) {
            Icon(Lucide.Search, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.search_message_content),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Icon(
                    Lucide.X, "Clear",
                    Modifier.size(20.dp).clickable { query = "" },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (query.isBlank() && history.isEmpty()) {
            // 空状态
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space8)) {
                    Icon(Lucide.Search, null, Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Text(
                        stringResource(R.string.search_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (query.isNotBlank() && hits.isEmpty()) {
            // 无匹配会话
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (query.isNotBlank()) {
            // ── 搜索结果：命中的会话 ──
            Column(Modifier.weight(1f).fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = Space12),
                ) {
                    Text(
                        stringResource(R.string.search_results),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(hits, key = { _, h -> h.session.id }) { _, hit ->
                        val title = hit.session.messages.firstOrNull { it.role == "user" }
                            ?.content?.replace('\n', ' ')?.take(24)
                            ?: stringResource(R.string.new_chat)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    SearchHistoryStore.add(context, query.trim())
                                    onOpenSession(hit.session.id)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space12),
                        ) {
                            Icon(Lucide.MessageSquare, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(hit.snippet, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        } else {
            // ── 搜索历史（查询为空时展示） ──
            Column(Modifier.weight(1f).fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = Space12),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.search_history),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (history.isNotEmpty()) {
                        Text(
                            stringResource(R.string.clear),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable {
                                SearchHistoryStore.clear(context)
                                history = emptyList()
                            },
                        )
                    }
                }

                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(history, key = { _, s -> s }) { _, item ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { query = item }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space12),
                        ) {
                            Icon(Lucide.Clock, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                item,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                Lucide.X,
                                stringResource(R.string.delete),
                                Modifier.size(16.dp).clickable {
                                    SearchHistoryStore.remove(context, item)
                                    history = SearchHistoryStore.getHistory(context)
                                },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        } // 外层 Column 结束
    }
}

// 生成命中片段：让关键词居中露出，前后各留一些上下文
private fun buildSnippet(content: String, query: String): String {
    val flat = content.replace('\n', ' ')
    val idx = flat.indexOf(query, ignoreCase = true)
    if (idx < 0) return flat.take(50)
    val start = (idx - 15).coerceAtLeast(0)
    val end = (idx + query.length + 35).coerceAtMost(flat.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < flat.length) "…" else ""
    return "$prefix${flat.substring(start, end)}$suffix"
}
