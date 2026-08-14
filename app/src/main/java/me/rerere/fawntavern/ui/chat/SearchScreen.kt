package me.rerere.fawntavern.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay
import me.rerere.fawntavern.R
import me.rerere.fawntavern.ui.components.AppTopBar
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12

@Composable
fun SearchScreen(
    charFile: String = "",
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val controller = remember(context) { ChatSearchController(AndroidChatSearchDataSource(context)) }
    var query by remember { mutableStateOf("") }
    var history by remember(controller) { mutableStateOf(controller.history()) }
    val focusRequester = remember { FocusRequester() }

    var hits by remember { mutableStateOf<List<ChatSearchHit>>(emptyList()) }
    LaunchedEffect(charFile, query) {
        val q = query.trim()
        if (q.isBlank()) {
            hits = emptyList()
            return@LaunchedEffect
        }
        hits = emptyList()
        delay(250)
        hits = controller.search(charFile, q)
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(stringResource(R.string.search_chats), onBack) }
    ) { padding ->
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
                AppIconButton(
                    icon = Lucide.X,
                    contentDescription = "Clear",
                    onClick = { query = "" },
                    size = 32.dp,
                    iconSize = 20.dp,
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
                    itemsIndexed(hits, key = { _, h -> h.sessionId }) { _, hit ->
                        val title = hit.title.ifBlank { stringResource(R.string.new_chat) }
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    controller.record(query)
                                    onOpenSession(hit.sessionId)
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
                                history = controller.clearHistory()
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
                            AppIconButton(
                                icon = Lucide.X,
                                contentDescription = stringResource(R.string.delete),
                                onClick = {
                                    history = controller.removeHistory(item)
                                },
                                size = 32.dp,
                                iconSize = 16.dp,
                            )
                        }
                    }
                }
            }
        }
        } // 外层 Column 结束
    }
}
