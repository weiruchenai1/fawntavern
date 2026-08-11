package me.rerere.fawntavern.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowDownToLine
import com.composables.icons.lucide.ArrowUpToLine
import com.composables.icons.lucide.SquareLibrary
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Package
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Smile
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiConfigStore
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.chat.ChatRepository
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.worldbook.WorldBookRepository
import me.rerere.fawntavern.ui.components.SettingsSubPage
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Composable
private fun DataCategory.label(): String = stringResource(labelResId)

private fun DataCategory.label(ctx: android.content.Context): String = ctx.getString(labelResId)

private data class DataCategory(
    val key: String,
    val labelResId: Int,
    val icon: @Composable () -> Unit,
    val itemCount: suspend () -> Int,
    val dir: (android.content.Context) -> File?,
    val clear: suspend (android.content.Context) -> Unit,
)

@Composable
fun DataManagementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showClearCategory by remember { mutableStateOf<DataCategory?>(null) }
    var showClearAll by remember { mutableStateOf(false) }
    var showResetApi by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    val categories = remember {
        listOf(
            DataCategory(
                key = "characters",
                labelResId = R.string.characters,
                icon = {
                    Icon(Lucide.Smile, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                itemCount = { CharacterRepository.listNames(context).size },
                dir = { CharacterRepository.charsDir(it) },
                clear = { ctx -> CharacterRepository.clear(ctx) },
            ),
            DataCategory(
                key = "presets",
                labelResId = R.string.presets,
                icon = {
                    Icon(Lucide.SlidersHorizontal, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                itemCount = { PresetRepository.listNames(context).size },
                dir = { PresetRepository.presetsDir(it) },
                clear = { ctx -> PresetRepository.clear(ctx) },
            ),
            DataCategory(
                key = "worldbooks",
                labelResId = R.string.world_books,
                icon = {
                    Icon(Lucide.SquareLibrary, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                itemCount = { WorldBookRepository.listNames(context).size },
                dir = { WorldBookRepository.worldDir(it) },
                clear = { ctx -> WorldBookRepository.clear(ctx) },
            ),
            DataCategory(
                key = "chats",
                labelResId = R.string.chat_history,
                icon = {
                    Icon(Lucide.MessageCircle, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                itemCount = { ChatRepository.list(context).size },
                dir = { ChatRepository.storageDir(it) },
                clear = { ctx -> ChatRepository.clear(ctx) },
            ),
        )
    }

    // 加载各分类的条目数与占用大小
    var catInfos by remember { mutableStateOf<List<Triple<DataCategory, Int, String>>>(emptyList()) }
    var apiCount by remember { mutableIntStateOf(0) }
    var totalItems by remember { mutableIntStateOf(0) }
    var totalSize by remember { mutableStateOf("0 B") }

    fun refresh() {
        scope.launch {
            working = true
            withContext(Dispatchers.IO) {
                catInfos = categories.map { cat ->
                    val count = cat.itemCount()
                    val size = cat.dir(context)?.let { dirSize(it) } ?: 0L
                    Triple(cat, count, formatSize(size))
                }
                apiCount = ApiConfigStore.loadConfig(context).providers.size
            }
            totalItems = catInfos.sumOf { it.second }
            totalSize = formatSize(catInfos.map { it.first.dir(context)?.let { dirSize(it) } ?: 0L }.sum())
            working = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    showClearCategory?.let { cat ->
        val cnt = catInfos.find { it.first.key == cat.key }?.second ?: 0
        AlertDialog(
            onDismissRequest = { showClearCategory = null },
            icon = {
                Icon(Lucide.TriangleAlert, null, Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error)
            },
            title = { Text(stringResource(R.string.clear_category_title_fmt, cat.label())) },
            text = { Text(stringResource(R.string.clear_category_msg_fmt, cnt, cat.label())) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        working = true
                        withContext(Dispatchers.IO) { cat.clear(context) }
                        refresh()
                        Toast.makeText(context, context.getString(R.string.toast_cleared_fmt, cat.label(context)), Toast.LENGTH_SHORT).show()
                    }
                    showClearCategory = null
                }) { Text(stringResource(R.string.clear_category_btn), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearCategory = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            icon = {
                Icon(Lucide.TriangleAlert, null, Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error)
            },
            title = { Text(stringResource(R.string.clear_all_title)) },
            text = { Text(stringResource(R.string.clear_all_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        working = true
                        withContext(Dispatchers.IO) {
                            categories.forEach { it.clear(context) }
                        }
                        refresh()
                        Toast.makeText(context, context.getString(R.string.toast_cleared_all), Toast.LENGTH_SHORT).show()
                    }
                    showClearAll = false
                }) { Text(stringResource(R.string.clear_all_btn), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearAll = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showResetApi) {
        AlertDialog(
            onDismissRequest = { showResetApi = false },
            title = { Text(stringResource(R.string.reset_api_title)) },
            text = { Text(stringResource(R.string.reset_api_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    apiCount = ApiConfigStore.resetToDefaults(context).providers.size
                    showResetApi = false
                    Toast.makeText(context, context.getString(R.string.toast_api_reset), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.reset_btn), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showResetApi = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // 导出全部（SAF CreateDocument → 打包为 ZIP）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                working = true
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            ZipOutputStream(out).use { zip ->
                                categories.forEach { cat ->
                                    cat.dir(context)?.listFiles()?.forEach { file ->
                                        zip.putNextEntry(ZipEntry("${cat.key}/${file.name}"))
                                        file.inputStream().use { it.copyTo(zip) }
                                        zip.closeEntry()
                                    }
                                }
                            }
                        }
                    }
                    Toast.makeText(context, context.getString(R.string.toast_export_success), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.toast_export_failed_fmt, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
                working = false
            }
        }
    }

    // 导入备份（SAF OpenDocument → 解包 ZIP，按目录前缀还原）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                working = true
                var restored = 0
                try {
                    withContext(Dispatchers.IO) {
                        // zip 条目前缀 → 落盘目录，与"导出全部"的打包格式对称（json、png 等
                        // 全部还原）。聊天记录（chats/）是打开中的 Room 数据库文件，运行期
                        // 覆盖会损坏数据库，跳过不恢复
                        val dirs = mapOf(
                            "characters" to CharacterRepository.charsDir(context),
                            "presets" to PresetRepository.presetsDir(context),
                            "worldbooks" to WorldBookRepository.worldDir(context),
                        )
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            java.util.zip.ZipInputStream(input).use { zip ->
                                var entry = zip.nextEntry
                                while (entry != null) {
                                    val parts = entry.name.split('/', limit = 2)
                                    val dir = if (parts.size == 2) dirs[parts[0]] else null
                                    val fileName = parts.getOrNull(1)
                                    // 只接受顶层分类目录直属的文件，拒绝子目录/路径穿越
                                    if (!entry.isDirectory && dir != null && !fileName.isNullOrBlank() &&
                                        !fileName.contains('/') && !fileName.contains('\\') && fileName != ".."
                                    ) {
                                        File(dir, fileName).outputStream().use { zip.copyTo(it) }
                                        restored++
                                    }
                                    zip.closeEntry()
                                    entry = zip.nextEntry
                                }
                            }
                        }
                    }
                    refresh()
                    Toast.makeText(context, context.getString(R.string.toast_imported_files_fmt, restored), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.toast_import_failed_fmt, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
                working = false
            }
        }
    }

    BackHandler(onBack = onBack)

    SettingsSubPage(stringResource(R.string.data_management), onBack) {
        SectionHeader(stringResource(R.string.storage_overview))
        OverviewCard(
            totalItems = totalItems,
            totalSize = totalSize,
            apiCount = apiCount,
        )

        SectionHeader(stringResource(R.string.category_management))
        catInfos.forEach { (cat, count, size) ->
            val fileCount = cat.dir(context)?.listFiles()?.size ?: 0
            CategoryCard(
                icon = cat.icon,
                label = cat.label(),
                count = count,
                size = size,
                fileCount = fileCount,
                onClear = { showClearCategory = cat },
                enabled = count > 0,
            )
        }

        SectionHeader(stringResource(R.string.api_config))
        ApiConfigCard(
            providerCount = apiCount,
            onReset = { showResetApi = true },
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        SectionHeader(stringResource(R.string.backup_restore))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space12),
        ) {
            OutlinedButton(
                onClick = {
                    importLauncher.launch(arrayOf(
                        "application/zip", "application/x-zip-compressed", "application/octet-stream"))
                },
                modifier = Modifier.weight(1f),
                enabled = !working,
            ) {
                Icon(Lucide.ArrowDownToLine, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Space8))
                Text(stringResource(R.string.import_backup))
            }
            OutlinedButton(
                onClick = { exportLauncher.launch("st-app-backup.zip") },
                modifier = Modifier.weight(1f),
                enabled = !working && totalItems > 0,
            ) {
                Icon(Lucide.ArrowUpToLine, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Space8))
                Text(stringResource(R.string.export_all))
            }
        }

        SectionHeader(stringResource(R.string.danger_zone))
        Button(
            onClick = { showClearAll = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !working && totalItems > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(Lucide.Trash2, null, Modifier.size(18.dp))
            Spacer(Modifier.width(Space8))
            Text(stringResource(R.string.clear_all_data))
        }

        if (working) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.processing), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun OverviewCard(totalItems: Int, totalSize: String, apiCount: Int) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Space16),
        verticalArrangement = Arrangement.spacedBy(Space12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space12)) {
            Icon(Lucide.Database, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.total_items_fmt, totalItems),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space8),
        ) {
            StatChip(stringResource(R.string.items_unit), stringResource(R.string.items_count_fmt, totalItems), Modifier.weight(1f))
            StatChip(stringResource(R.string.space_unit), totalSize, Modifier.weight(1f))
            StatChip(stringResource(R.string.api_unit), stringResource(R.string.providers_count_fmt, apiCount), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = Space12, vertical = Space8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space4),
    ) {
        Text(value, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CategoryCard(
    icon: @Composable () -> Unit,
    label: String,
    count: Int,
    size: String,
    fileCount: Int,
    onClear: () -> Unit,
    enabled: Boolean,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Space16),
        verticalArrangement = Arrangement.spacedBy(Space12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space12)) {
            icon()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                Text(label, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(if (enabled) buildString {
                    append(stringResource(R.string.items_with_count_fmt, count))
                    if (fileCount > 0) append(" · $size")
                } else stringResource(R.string.no_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (enabled) {
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Lucide.Trash2, null, Modifier.size(16.dp))
                Spacer(Modifier.width(Space4))
                Text("${stringResource(R.string.clear_all_prefix)}${label}")
            }
        }
    }
}

@Composable
private fun ApiConfigCard(providerCount: Int, onReset: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Space16),
        verticalArrangement = Arrangement.spacedBy(Space12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space12)) {
            Icon(Lucide.Package, null, Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                Text(stringResource(R.string.api_config), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.providers_unit_fmt, providerCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Lucide.Trash2, null, Modifier.size(16.dp))
            Spacer(Modifier.width(Space4))
            Text(stringResource(R.string.reset_to_default))
        }
    }
}

private fun dirSize(dir: File): Long {
    var total = 0L
    dir.listFiles()?.forEach { f ->
        if (f.isFile) total += f.length()
        else if (f.isDirectory) total += dirSize(f)
    }
    return total
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    else -> "%.1f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
}
