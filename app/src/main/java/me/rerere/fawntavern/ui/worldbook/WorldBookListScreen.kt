package me.rerere.fawntavern.ui.worldbook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SlidersHorizontal
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.CreateItemSpec
import me.rerere.fawntavern.ui.components.ImportableListScreen
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.components.appClickable

@Composable
fun WorldBookListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { WorldBookDataController(context) }
    var selectedBook by remember { mutableStateOf<WorldBook?>(null) }
    var showWiSettings by remember { mutableStateOf(false) }
    // SaveableStateHolder：进入编辑器/设置时列表离开组合，其 LazyListState 被暂存；
    // 返回时恢复，避免列表滚动位置丢失（跳回顶部）。
    val stateHolder = rememberSaveableStateHolder()

    if (selectedBook != null) {
        stateHolder.SaveableStateProvider("editor") {
            BackHandler { selectedBook = null }
            WorldBookViewScreen(book = selectedBook!!, onBack = { selectedBook = null })
        }
        return
    }

    if (showWiSettings) {
        stateHolder.SaveableStateProvider("settings") {
            BackHandler { showWiSettings = false }
            WorldInfoSettingsScreen(onBack = { showWiSettings = false })
        }
        return
    }

    stateHolder.SaveableStateProvider("list") {
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
        listNames = controller::names,
        loadItem = controller::load,
        importItem = controller::import,
        renameItem = controller::rename,
        deleteItem = controller::delete,
        exportItem = controller::exportJson,
        onOpen = { selectedBook = it },
        createItem = CreateItemSpec(
            titleRes = R.string.add_worldbook,
            nameLabelRes = R.string.worldbook_name_label,
            importLabelRes = R.string.import_worldbook,
            createdToastRes = R.string.worldbook_created,
            create = { controller.create(it) },
        ),
        actions = {
            AppIconButton(
                icon = Lucide.SlidersHorizontal,
                contentDescription = stringResource(R.string.wi_activation_settings),
                onClick = { showWiSettings = true },
                size = 32.dp,
                iconSize = 24.dp,
            )
        },
        itemCard = { _, book, onClick, onLongPress ->
            BookCard(book, onClick = onClick, onLongPress = onLongPress)
        },
    )
    } // SaveableStateProvider("list")
}

@Composable
private fun BookCard(book: WorldBook, onClick: () -> Unit, onLongPress: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .appClickable(onClick = onClick, onLongClick = onLongPress)
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
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.entries_count_fmt, book.entries.size, book.entries.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Lucide.ChevronRight, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
