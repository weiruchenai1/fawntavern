package me.rerere.stapp.ui.worldbook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.Lucide
import me.rerere.stapp.R
import me.rerere.stapp.data.worldbook.WorldBook
import me.rerere.stapp.data.worldbook.WorldBookRepository
import me.rerere.stapp.ui.components.ImportableListScreen
import me.rerere.stapp.ui.components.Space12
import me.rerere.stapp.ui.components.Space16

@Composable
fun WorldBookListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedBook by remember { mutableStateOf<WorldBook?>(null) }

    if (selectedBook != null) {
        BackHandler { selectedBook = null }
        WorldBookViewScreen(book = selectedBook!!, onBack = { selectedBook = null })
        return
    }

    BackHandler(onBack = onBack)

    ImportableListScreen(
        titleRes = R.string.world_books,
        onBack = onBack,
        importMimeType = "application/json",
        emptyIcon = Lucide.FileJson,
        emptyTitleRes = R.string.no_worldbooks_title,
        emptyDescRes = R.string.no_worldbooks_desc,
        renameLabelRes = R.string.toast_rename_name_label,
        deleteTitleRes = R.string.delete_worldbook_title,
        deleteMsgFmtRes = R.string.delete_worldbook_msg_fmt,
        listNames = { WorldBookRepository.listNames(context) },
        loadItem = { WorldBookRepository.load(context, it) },
        importItem = { WorldBookRepository.import(context, it).name },
        renameItem = { old, new -> WorldBookRepository.rename(context, old, new) },
        deleteItem = { WorldBookRepository.delete(context, it) },
        onOpen = { selectedBook = it },
        itemCard = { _, book, onClick, onLongPress ->
            BookCard(book, onClick = onClick, onLongPress = onLongPress)
        },
    )
}

@Composable
private fun BookCard(book: WorldBook, onClick: () -> Unit, onLongPress: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress() }) }
            .padding(Space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.BookOpen, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Space12))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.name, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.entries_count_fmt, book.entries.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Lucide.ChevronRight, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
