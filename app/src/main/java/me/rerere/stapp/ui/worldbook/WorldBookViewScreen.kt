package me.rerere.stapp.ui.worldbook

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.stapp.R
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleChevronLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Trash2
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import me.rerere.stapp.data.worldbook.WorldBook
import me.rerere.stapp.data.worldbook.WorldBookEntry
import me.rerere.stapp.data.worldbook.WorldBookRepository

private val Space4 = 4.dp
private val Space8 = 8.dp
private val Space12 = 12.dp
private val Space16 = 16.dp

@Composable
fun WorldBookViewScreen(book: WorldBook, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expandedId by remember { mutableStateOf<Int?>(null) }
    var editingEntry by remember { mutableStateOf<WorldBookEntry?>(null) }
    var deletingEntry by remember { mutableStateOf<WorldBookEntry?>(null) }
    var entries by remember { mutableStateOf(book.entries.values.sortedBy { it.insertionOrder }) }

    fun saveBook() {
        scope.launch { WorldBookRepository.saveEntries(context, book.name, entries) }
    }

    editingEntry?.let { entry ->
        var eComment by remember(entry) { mutableStateOf(entry.comment) }
        var eContent by remember(entry) { mutableStateOf(entry.content) }
        var eKeys by remember(entry) { mutableStateOf(entry.keys.joinToString(", ")) }
        var eEnabled by remember(entry) { mutableStateOf(entry.enabled) }
        AlertDialog(
            onDismissRequest = { editingEntry = null },
            title = { Text(stringResource(R.string.edit_entry)) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 500.dp),
                    verticalArrangement = Arrangement.spacedBy(Space8)) {
                    OutlinedTextField(eComment, { eComment = it }, label = { Text(stringResource(R.string.entry_name_label)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(eKeys, { eKeys = it }, label = { Text(stringResource(R.string.entry_keys_label)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.entry_enabled), style = MaterialTheme.typography.bodyMedium)
                        Switch(eEnabled, { eEnabled = it })
                    }
                    OutlinedTextField(eContent, { eContent = it }, label = { Text(stringResource(R.string.entry_content_label)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                }
            },
            confirmButton = {
                Button(onClick = {
                    val idx = entries.indexOfFirst { it.id == entry.id }
                    if (idx >= 0) {
                        entries = entries.toMutableList().also {
                            it[idx] = entry.copy(
                                comment = eComment, content = eContent,
                                keys = eKeys.split(",").map { k -> k.trim() }.filter { it.isNotBlank() },
                                enabled = eEnabled,
                            )
                        }
                        saveBook()
                    }
                    editingEntry = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editingEntry = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    deletingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deletingEntry = null },
            title = { Text(stringResource(R.string.delete_entry_title)) },
            text = { Text(stringResource(R.string.delete_entry_msg_fmt, entry.comment.ifBlank { "Entry ${entry.id}" })) },
            confirmButton = {
                TextButton(onClick = {
                    entries = entries.filter { it.id != entry.id }
                    saveBook()
                    deletingEntry = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingEntry = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.CircleChevronLeft, stringResource(R.string.back), Modifier.size(24.dp).clickable { onBack() },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(book.name, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.size(24.dp))
            }
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_entries), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(Space8),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                itemsIndexed(entries, key = { _, e -> e.id }) { _, entry ->
                    val expanded = expandedId == entry.id
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .animateContentSize()
                            .padding(Space12),
                        verticalArrangement = Arrangement.spacedBy(Space8),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 点击名称区域展开/收起
                            Column(Modifier.weight(1f).clickable {
                                expandedId = if (expanded) null else entry.id
                            }) {
                                Text(entry.comment.ifBlank { "Entry ${entry.id}" },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface)
                                if (entry.keys.isNotEmpty()) {
                                    Text(entry.keys.take(8).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Text(if (entry.enabled) stringResource(R.string.enabled_status_on) else stringResource(R.string.enabled_status_off),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (entry.enabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Lucide.Pencil, stringResource(R.string.edit), Modifier.size(16.dp).clickable { editingEntry = entry },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(Space8))
                            Icon(Lucide.Trash2, stringResource(R.string.delete), Modifier.size(16.dp).clickable { deletingEntry = entry },
                                tint = MaterialTheme.colorScheme.error)
                        }
                        if (expanded) {
                            Text(entry.content, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
