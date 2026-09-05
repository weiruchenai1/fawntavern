package me.rerere.fawntavern.ui.character

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SlidersHorizontal
import me.rerere.fawntavern.R
import me.rerere.fawntavern.ui.components.PickerRow
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.settings.ModelCard

@Composable
internal fun PresetSelector(
    controller: CharacterEditorController,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var options by remember { mutableStateOf<List<CharacterAssociationOption>>(emptyList()) }
    var showSheet by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { options = controller.presetOptions() }

    ModelCard(
        icon = Lucide.SlidersHorizontal,
        title = stringResource(R.string.assoc_preset),
        subtitle = stringResource(R.string.assoc_preset_desc),
        iconKey = "",
        selectionIcon = Lucide.SlidersHorizontal,
        displayName = options.firstOrNull { it.id == selectedId }?.label
            ?: if (selectedId.isBlank()) stringResource(R.string.assoc_none) else stringResource(R.string.assoc_missing),
        showReset = selectedId.isNotBlank(),
        showBolt = false,
        onPick = { showSheet = true },
        onReset = { onSelect("") },
    )
    if (showSheet) {
        AssociationPickerSheet(
            title = stringResource(R.string.assoc_preset),
            optionIcon = Lucide.SlidersHorizontal,
            options = options.map { AssociationOption(it.id, it.label) },
            selectedIds = setOfNotNull(selectedId.takeIf(String::isNotBlank)),
            multiSelect = false,
            onConfirm = {
                onSelect(it.firstOrNull().orEmpty())
                showSheet = false
            },
            onDismiss = { showSheet = false },
        )
    }
}

@Composable
internal fun RegexSelector(
    controller: CharacterEditorController,
    selectedIds: List<String>,
    onSelect: (List<String>) -> Unit,
) {
    var options by remember { mutableStateOf<List<CharacterAssociationOption>>(emptyList()) }
    var showSheet by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { options = controller.regexOptions() }
    val displayName = when (selectedIds.size) {
        0 -> stringResource(R.string.assoc_none)
        1 -> options.firstOrNull { it.id == selectedIds.first() }?.label ?: stringResource(R.string.assoc_missing)
        else -> stringResource(R.string.assoc_selected_count, selectedIds.size)
    }
    ModelCard(
        icon = Lucide.FileJson,
        title = stringResource(R.string.assoc_regex),
        subtitle = stringResource(R.string.assoc_regex_desc),
        iconKey = "",
        selectionIcon = Lucide.FileJson,
        displayName = displayName,
        showReset = selectedIds.isNotEmpty(),
        showBolt = false,
        onPick = { showSheet = true },
        onReset = { onSelect(emptyList()) },
    )
    if (showSheet) {
        AssociationPickerSheet(
            title = stringResource(R.string.assoc_regex),
            optionIcon = Lucide.FileJson,
            options = options.map { AssociationOption(it.id, it.label) },
            selectedIds = selectedIds.toSet(),
            multiSelect = true,
            onConfirm = {
                onSelect(it.toList())
                showSheet = false
            },
            onDismiss = { showSheet = false },
        )
    }
}

@Composable
internal fun WorldBookSelector(
    controller: CharacterEditorController,
    selectedIds: List<String>,
    onChange: (List<String>) -> Unit,
) {
    var options by remember { mutableStateOf<List<CharacterAssociationOption>>(emptyList()) }
    var showSheet by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { options = controller.worldBookOptions() }
    val displayName = when (selectedIds.size) {
        0 -> stringResource(R.string.assoc_none)
        1 -> options.firstOrNull { it.id == selectedIds.first() }?.label ?: stringResource(R.string.assoc_missing)
        else -> stringResource(R.string.assoc_selected_count, selectedIds.size)
    }
    ModelCard(
        icon = Lucide.BookOpen,
        title = stringResource(R.string.assoc_worldbooks),
        subtitle = stringResource(R.string.assoc_worldbooks_desc),
        iconKey = "",
        selectionIcon = Lucide.BookOpen,
        displayName = displayName,
        showReset = selectedIds.isNotEmpty(),
        showBolt = false,
        onPick = { showSheet = true },
        onReset = { onChange(emptyList()) },
    )
    if (showSheet) {
        AssociationPickerSheet(
            title = stringResource(R.string.assoc_worldbooks),
            optionIcon = Lucide.BookOpen,
            options = options.map { AssociationOption(it.id, it.label) },
            selectedIds = selectedIds.toSet(),
            multiSelect = true,
            onConfirm = { selected ->
                onChange(options.map { it.id }.filter(selected::contains))
                showSheet = false
            },
            onDismiss = { showSheet = false },
        )
    }
}

private data class AssociationOption(val id: String, val label: String)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AssociationPickerSheet(
    title: String,
    optionIcon: ImageVector,
    options: List<AssociationOption>,
    selectedIds: Set<String>,
    multiSelect: Boolean,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val optionIds = remember(options) { options.mapTo(mutableSetOf()) { it.id } }
    var draftSelection by remember(title, selectedIds, optionIds) { mutableStateOf(selectedIds.intersect(optionIds)) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space16).padding(bottom = Space16),
            verticalArrangement = Arrangement.spacedBy(Space12),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space12),
            ) {
                options.forEach { option ->
                    val selected = option.id in draftSelection
                    PickerRow(
                        selected = selected,
                        onClick = {
                            draftSelection = if (multiSelect) {
                                if (selected) draftSelection - option.id else draftSelection + option.id
                            } else setOf(option.id)
                        },
                        icon = {
                            Icon(
                                optionIcon,
                                null,
                                Modifier.size(24.dp),
                                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        label = {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailing = {
                            if (selected) {
                                Icon(Lucide.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        },
                    )
                }
                if (options.isEmpty()) {
                    Text(
                        stringResource(R.string.assoc_no_options),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space8, vertical = Space4),
                    )
                }
            }
            Button(onClick = { onConfirm(draftSelection) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Lucide.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Space8))
                Text(stringResource(R.string.confirm))
            }
        }
    }
}

private val RoleOptions = listOf("system", "user", "assistant")

@Composable
private fun roleLabel(role: String): String = when (role) {
    "user" -> stringResource(R.string.role_user)
    "assistant" -> stringResource(R.string.role_assistant)
    else -> stringResource(R.string.role_system)
}

@Composable
internal fun RoleDropdown(
    label: String,
    current: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = roleLabel(current),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { Icon(Lucide.ChevronDown, null, Modifier.size(20.dp)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RoleOptions.forEach { role ->
                DropdownMenuItem(
                    text = { Text(roleLabel(role)) },
                    onClick = {
                        onChange(role)
                        expanded = false
                    },
                )
            }
        }
    }
}
