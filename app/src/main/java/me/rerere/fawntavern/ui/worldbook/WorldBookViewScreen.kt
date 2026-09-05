package me.rerere.fawntavern.ui.worldbook

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.fawntavern.R
import me.rerere.fawntavern.di.LocalAppContainer
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.data.worldbook.WorldBookEntry
import me.rerere.fawntavern.data.worldbook.WorldBookPos
import me.rerere.fawntavern.ui.components.AppTopBar
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.AppTextArea
import me.rerere.fawntavern.ui.components.ConfirmDeleteDialog
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.components.noRippleClickable

/** 位置下拉选项：展示用字符串资源 → (position, role)。at_depth 按 role 拆成三项。 */
private val POSITION_OPTIONS = listOf(
    Triple(R.string.pos_before_char, WorldBookPos.BEFORE_CHAR, 0),
    Triple(R.string.pos_after_char, WorldBookPos.AFTER_CHAR, 0),
    Triple(R.string.pos_em_top, WorldBookPos.EM_TOP, 0),
    Triple(R.string.pos_em_bottom, WorldBookPos.EM_BOTTOM, 0),
    Triple(R.string.pos_an_top, WorldBookPos.AN_TOP, 0),
    Triple(R.string.pos_an_bottom, WorldBookPos.AN_BOTTOM, 0),
    Triple(R.string.pos_at_depth_system, WorldBookPos.AT_DEPTH, 0),
    Triple(R.string.pos_at_depth_user, WorldBookPos.AT_DEPTH, 1),
    Triple(R.string.pos_at_depth_assistant, WorldBookPos.AT_DEPTH, 2),
    Triple(R.string.pos_outlet, WorldBookPos.OUTLET, 0),
)

/** 次级关键词逻辑：selectiveLogic 值 → 展示用字符串资源 */
private val LOGICS = listOf(
    0 to R.string.logic_and_any,
    1 to R.string.logic_not_all,
    2 to R.string.logic_not_any,
    3 to R.string.logic_and_all,
)

/** 三态状态 */
private enum class WiStatus { CONSTANT, KEYWORD, VECTORIZED }

@Composable
fun WorldBookViewScreen(book: WorldBook, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val controller = LocalAppContainer.current.features.worldBooks
    val saveCoordinator = remember(book.name, controller) {
        WorldBookSaveCoordinator { entries -> controller.saveEntries(book.name, entries) }
    }
    var expandedId by remember { mutableStateOf<Int?>(null) }
    var editingEntry by remember { mutableStateOf<WorldBookEntry?>(null) }
    var deletingEntry by remember { mutableStateOf<WorldBookEntry?>(null) }
    // 按文件/解析顺序展示（= ST 的 display_index 顺序）；insertion_order 是注入顺序，不用于列表排序
    var entries by remember { mutableStateOf(book.entries.values.toList()) }
    var leaving by remember { mutableStateOf(false) }

    fun saveBook() {
        val request = saveCoordinator.request(entries)
        scope.launch {
            try {
                saveCoordinator.saveLatest(request)
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    resources.getString(R.string.worldbook_save_failed_fmt, error.message.orEmpty()),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun leaveAfterSave() {
        if (leaving) return
        leaving = true
        val request = saveCoordinator.request(entries)
        scope.launch {
            try {
                saveCoordinator.saveLatest(request)
                onBack()
            } catch (error: Exception) {
                leaving = false
                Toast.makeText(
                    context,
                    resources.getString(R.string.worldbook_save_failed_fmt, error.message.orEmpty()),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // 新条目 id 取现有最大值 +1：saveEntries 按 id patch 原文件，撞号会覆盖别的条目
    fun newEntry() = WorldBookEntry(
        id = (entries.maxOfOrNull { it.id } ?: -1) + 1,
        keys = emptyList(),
        comment = "",
        content = "",
    )

    editingEntry?.let { entry ->
        EntryEditDialog(
            entry = entry,
            // 列表里没有这个 id = 从「添加条目」进来的新条目，保存时才真正入列
            isNew = entries.none { it.id == entry.id },
            onDismiss = { editingEntry = null },
            onSave = { updated ->
                val idx = entries.indexOfFirst { it.id == entry.id }
                entries = if (idx >= 0) entries.toMutableList().also { it[idx] = updated }
                          else entries + updated
                saveBook()
                editingEntry = null
            },
        )
    }

    deletingEntry?.let { entry ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.delete_entry_title),
            text = stringResource(R.string.delete_entry_msg_fmt, entry.comment.ifBlank { "Entry ${entry.id}" }),
            onConfirm = {
                entries = entries.filter { it.id != entry.id }
                saveBook()
                deletingEntry = null
            },
            onDismiss = { deletingEntry = null },
        )
    }

    BackHandler(onBack = { leaveAfterSave() })

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = book.name, onBack = { leaveAfterSave() }) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Space8),
        ) {
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
                        // 状态徽标（永久/向量化）
                        statusBadge(entry)?.let { (label, color) ->
                            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
                            Spacer(Modifier.width(Space8))
                        }
                        Text(if (entry.enabled) stringResource(R.string.enabled_status_on) else stringResource(R.string.enabled_status_off),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry.enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                        AppIconButton(
                            icon = Lucide.Pencil,
                            contentDescription = stringResource(R.string.edit),
                            onClick = { editingEntry = entry },
                            size = 32.dp,
                            iconSize = 16.dp,
                        )
                        AppIconButton(
                            icon = Lucide.Trash2,
                            contentDescription = stringResource(R.string.delete),
                            onClick = { deletingEntry = entry },
                            tint = MaterialTheme.colorScheme.error,
                            size = 32.dp,
                            iconSize = 16.dp,
                        )
                    }
                    if (expanded) {
                        Text(entry.content, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (entries.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_entries),
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { editingEntry = newEntry() }.padding(Space16),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.Plus, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(Space8))
                    Text(stringResource(R.string.add_entry), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** 列表项的状态徽标（永久蓝 / 向量化）；关键词态不显示 */
@Composable
private fun statusBadge(entry: WorldBookEntry): Pair<String, androidx.compose.ui.graphics.Color>? = when {
    entry.constant -> stringResource(R.string.status_constant) to MaterialTheme.colorScheme.tertiary
    entry.vectorized -> stringResource(R.string.status_vectorized) to MaterialTheme.colorScheme.secondary
    else -> null
}

@Composable
private fun EntryEditDialog(
    entry: WorldBookEntry,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (WorldBookEntry) -> Unit,
) {
    var eComment by remember(entry) { mutableStateOf(entry.comment) }
    val eContent = remember(entry) { TextFieldState(entry.content) }
    var eKeys by remember(entry) { mutableStateOf(entry.keys.joinToString(", ")) }
    var eSecondary by remember(entry) { mutableStateOf(entry.keySecondary.joinToString(", ")) }
    var eEnabled by remember(entry) { mutableStateOf(entry.enabled) }
    var eStatus by remember(entry) {
        mutableStateOf(when { entry.constant -> WiStatus.CONSTANT; entry.vectorized -> WiStatus.VECTORIZED; else -> WiStatus.KEYWORD })
    }
    var ePosition by remember(entry) { mutableStateOf(entry.position) }
    var eRole by remember(entry) { mutableIntStateOf(entry.role) }
    var eOrder by remember(entry) { mutableStateOf(entry.insertionOrder.toString()) }
    var eDepth by remember(entry) { mutableStateOf(entry.depth.toString()) }
    var eOutlet by remember(entry) { mutableStateOf(entry.outletName) }
    var eLogic by remember(entry) { mutableIntStateOf(entry.selectiveLogic) }
    var eProbability by remember(entry) { mutableIntStateOf(entry.probability) }
    // 高级
    var showAdvanced by remember(entry) { mutableStateOf(false) }
    var eScanDepth by remember(entry) { mutableStateOf(entry.scanDepth?.toString() ?: "") }
    var eCase by remember(entry) { mutableStateOf(entry.caseSensitive) }
    var eWholeWords by remember(entry) { mutableStateOf(entry.matchWholeWords) }
    var eExcludeRec by remember(entry) { mutableStateOf(entry.excludeRecursion) }
    var ePreventRec by remember(entry) { mutableStateOf(entry.preventRecursion) }
    var eDelayRec by remember(entry) { mutableStateOf(entry.delayUntilRecursion) }
    var eGroup by remember(entry) { mutableStateOf(entry.group) }
    var eGroupWeight by remember(entry) { mutableStateOf(entry.groupWeight.toString()) }
    var eGroupOverride by remember(entry) { mutableStateOf(entry.groupOverride) }
    var eGroupScoring by remember(entry) { mutableStateOf(entry.useGroupScoring) }
    var eSticky by remember(entry) { mutableStateOf(entry.sticky.toString()) }
    var eCooldown by remember(entry) { mutableStateOf(entry.cooldown.toString()) }
    var eDelay by remember(entry) { mutableStateOf(entry.delay.toString()) }

    val isAtDepth = ePosition == WorldBookPos.AT_DEPTH
    val isOutlet = ePosition == WorldBookPos.OUTLET

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                modifier = Modifier.imePadding(),
                topBar = {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            stringResource(if (isNew) R.string.add_entry else R.string.edit_entry),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        )
                    }
                },
                bottomBar = {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                    ) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                        Button(onClick = {
                            onSave(entry.copy(
                                comment = eComment, content = eContent.text.toString(),
                                keys = eKeys.split(",").map { it.trim() }.filter { it.isNotBlank() },
                                keySecondary = eSecondary.split(",").map { it.trim() }.filter { it.isNotBlank() },
                                enabled = eEnabled,
                                constant = eStatus == WiStatus.CONSTANT,
                                vectorized = eStatus == WiStatus.VECTORIZED,
                                position = ePosition, role = eRole,
                                insertionOrder = eOrder.toIntOrNull() ?: entry.insertionOrder,
                                depth = eDepth.toIntOrNull() ?: entry.depth,
                                outletName = eOutlet.trim(),
                                selectiveLogic = eLogic, probability = eProbability,
                                scanDepth = eScanDepth.toIntOrNull()?.takeIf { it > 0 },
                                caseSensitive = eCase, matchWholeWords = eWholeWords,
                                excludeRecursion = eExcludeRec, preventRecursion = ePreventRec, delayUntilRecursion = eDelayRec,
                                group = eGroup.trim(), groupWeight = eGroupWeight.toIntOrNull() ?: entry.groupWeight,
                                groupOverride = eGroupOverride, useGroupScoring = eGroupScoring,
                                sticky = eSticky.toIntOrNull() ?: 0,
                                cooldown = eCooldown.toIntOrNull() ?: 0,
                                delay = eDelay.toIntOrNull() ?: 0,
                            ))
                        }) { Text(stringResource(R.string.save)) }
                    }
                }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(Space8)
                ) {
                    OutlinedTextField(eComment, { eComment = it }, label = { Text(stringResource(R.string.entry_name_label)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    // 三态状态
                    StatusSelector(eStatus) { eStatus = it }
                    if (eStatus == WiStatus.VECTORIZED) {
                        Text(stringResource(R.string.status_vectorized_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    if (eStatus != WiStatus.CONSTANT) {
                        OutlinedTextField(eKeys, { eKeys = it }, label = { Text(stringResource(R.string.entry_keys_label)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(eSecondary, { eSecondary = it }, label = { Text(stringResource(R.string.entry_secondary_keys)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        val logicLabels = LOGICS.map { stringResource(it.second) }
                        EntryDropdown(stringResource(R.string.entry_selective_logic),
                            stringResource(LOGICS.first { it.first == eLogic }.second), logicLabels) { label ->
                            eLogic = LOGICS[logicLabels.indexOf(label)].first
                        }
                    }
                    EntryRow(stringResource(R.string.entry_enabled)) { Switch(eEnabled, { eEnabled = it }) }
                    // 位置（含 @D 角色拆项）
                    val posLabels = POSITION_OPTIONS.map { stringResource(it.first) }
                    val curIdx = POSITION_OPTIONS.indexOfFirst {
                        it.second == ePosition && (it.second != WorldBookPos.AT_DEPTH || it.third == eRole)
                    }.let { if (it < 0) 1 else it }
                    EntryDropdown(stringResource(R.string.entry_position), posLabels[curIdx], posLabels) { label ->
                        val opt = POSITION_OPTIONS[posLabels.indexOf(label)]
                        ePosition = opt.second; eRole = opt.third
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space8)) {
                        OutlinedTextField(eOrder, { eOrder = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.entry_order)) },
                            singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        if (isAtDepth) {
                            OutlinedTextField(eDepth, { eDepth = it.filter { c -> c.isDigit() } },
                                label = { Text(stringResource(R.string.entry_depth)) },
                                singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }
                    if (isOutlet) {
                        OutlinedTextField(eOutlet, { eOutlet = it }, label = { Text(stringResource(R.string.entry_outlet_name)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    if (eStatus != WiStatus.CONSTANT) {
                        Column {
                            Text("${stringResource(R.string.entry_probability)}: $eProbability%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(eProbability.toFloat(), { eProbability = it.toInt() }, valueRange = 0f..100f)
                        }
                    }
                    AppTextArea(
                        state = eContent,
                        label = stringResource(R.string.entry_content_label),
                        minLines = 5, maxLines = 14,
                    )

                    // ── 高级（默认折叠） ──
                    Row(Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.entry_advanced), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                        Icon(if (showAdvanced) Lucide.ChevronDown else Lucide.ChevronRight, null,
                            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (showAdvanced) {
                        OutlinedTextField(eScanDepth, { eScanDepth = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.entry_scan_depth)) },
                            placeholder = { Text(stringResource(R.string.tri_follow_global)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        TriStateDropdown(stringResource(R.string.entry_case_sensitive), eCase) { eCase = it }
                        TriStateDropdown(stringResource(R.string.entry_match_whole_words), eWholeWords) { eWholeWords = it }
                        // 递归
                        SectionLabel(stringResource(R.string.entry_recursion))
                        EntryRow(stringResource(R.string.entry_exclude_recursion)) { Switch(eExcludeRec, { eExcludeRec = it }) }
                        EntryRow(stringResource(R.string.entry_prevent_recursion)) { Switch(ePreventRec, { ePreventRec = it }) }
                        EntryRow(stringResource(R.string.entry_delay_until_recursion)) { Switch(eDelayRec, { eDelayRec = it }) }
                        // 包含组
                        SectionLabel(stringResource(R.string.entry_group))
                        OutlinedTextField(eGroup, { eGroup = it }, label = { Text(stringResource(R.string.entry_group)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(eGroupWeight, { eGroupWeight = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.entry_group_weight)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        EntryRow(stringResource(R.string.entry_group_override)) { Switch(eGroupOverride, { eGroupOverride = it }) }
                        EntryRow(stringResource(R.string.entry_use_group_scoring)) { Switch(eGroupScoring, { eGroupScoring = it }) }
                        // 定时
                        SectionLabel(stringResource(R.string.entry_timed))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space8)) {
                            OutlinedTextField(eSticky, { eSticky = it.filter { c -> c.isDigit() } },
                                label = { Text(stringResource(R.string.entry_sticky)) }, singleLine = true,
                                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(eCooldown, { eCooldown = it.filter { c -> c.isDigit() } },
                                label = { Text(stringResource(R.string.entry_cooldown)) }, singleLine = true,
                                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(eDelay, { eDelay = it.filter { c -> c.isDigit() } },
                                label = { Text(stringResource(R.string.entry_delay)) }, singleLine = true,
                                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }
                }
            }
        }
    }
}

/** 三态状态分段选择器：永久 / 关键词 / 向量化（互斥） */
@Composable
private fun StatusSelector(status: WiStatus, onChange: (WiStatus) -> Unit) {
    val options = listOf(
        WiStatus.CONSTANT to R.string.status_constant,
        WiStatus.KEYWORD to R.string.status_keyword,
        WiStatus.VECTORIZED to R.string.status_vectorized,
    )
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(2.dp),
    ) {
        options.forEach { (st, res) ->
            val sel = st == status
            Text(
                stringResource(res),
                style = MaterialTheme.typography.labelMedium,
                color = if (sel) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .noRippleClickable { onChange(st) }
                    .padding(vertical = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 三态布尔（跟随全局 / 开 / 关）→ Boolean? */
@Composable
private fun TriStateDropdown(label: String, value: Boolean?, onChange: (Boolean?) -> Unit) {
    val options = listOf<Pair<Boolean?, Int>>(
        null to R.string.tri_follow_global,
        true to R.string.tri_on,
        false to R.string.tri_off,
    )
    val labels = options.map { stringResource(it.second) }
    val current = stringResource(options.first { it.first == value }.second)
    EntryDropdown(label, current, labels) { label2 -> onChange(options[labels.indexOf(label2)].first) }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Space4))
}

/** 条目编辑器：一行「标签 + 右侧控件」 */
@Composable
private fun EntryRow(label: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        control()
    }
}

/** 条目编辑器：一行「标签 + 下拉选择」 */
@Composable
private fun EntryDropdown(label: String, current: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    EntryRow(label) {
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { expanded = true }
                    .padding(horizontal = Space12, vertical = Space8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space4),
            ) {
                Text(current, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Icon(Lucide.ChevronDown, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(text = { Text(opt) }, onClick = { onChange(opt); expanded = false })
                }
            }
        }
    }
}
