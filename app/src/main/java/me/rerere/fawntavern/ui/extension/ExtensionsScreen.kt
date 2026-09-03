package me.rerere.fawntavern.ui.extension

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import me.rerere.fawntavern.R
import me.rerere.fawntavern.extension.Extension
import me.rerere.fawntavern.extension.ExtensionHost
import me.rerere.fawntavern.extension.QuickReply
import me.rerere.fawntavern.extension.builtin.QuickReplyExtension
import me.rerere.fawntavern.extension.builtin.SummarizeExtension
import me.rerere.fawntavern.plugin.PluginManager
import me.rerere.fawntavern.plugin.PluginConfigField
import me.rerere.fawntavern.plugin.PluginConfigSchema
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.AppTextArea
import me.rerere.fawntavern.ui.components.SettingsSubPage
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 有独立设置界面的内置扩展 id */
private val HAS_SETTINGS = setOf(SummarizeExtension.ID, QuickReplyExtension.ID)
private const val PLUGIN_CONFIG_VALUE_CHARS = 256

/**
 * 扩展管理页：列出已登记扩展、开关启用、进入各自设置。设置页以本文件内嵌套导航呈现
 * （仿 WorldInfoSettingsScreen 挂在世界书列表下），不占用 ChatScreen 的 Screen 枚举。
 */
@Composable
fun ExtensionsScreen(onBack: () -> Unit) {
    var settingsFor by remember { mutableStateOf<String?>(null) }
    // SaveableStateHolder：进入设置页时列表离开组合，其 ScrollState 被暂存；
    // 返回时恢复，避免列表滚动位置丢失（跳回顶部）。
    val stateHolder = rememberSaveableStateHolder()
    when (val target = settingsFor) {
        SummarizeExtension.ID -> stateHolder.SaveableStateProvider("settings") {
            SummarizeSettings(onBack = { settingsFor = null })
        }
        QuickReplyExtension.ID -> stateHolder.SaveableStateProvider("settings") {
            QuickReplySettings(onBack = { settingsFor = null })
        }
        null -> stateHolder.SaveableStateProvider("list") {
            ExtensionsList(onBack = onBack, onOpenSettings = { settingsFor = it })
        }
        else -> stateHolder.SaveableStateProvider("plugin-$target") {
            PluginSettings(pluginId = target, onBack = { settingsFor = null })
        }
    }
}

@Composable
private fun ExtensionsList(onBack: () -> Unit, onOpenSettings: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val pluginInstallFailedMessage = stringResource(R.string.plugin_install_failed)
    val pluginOpenFailedMessage = stringResource(R.string.plugin_open_failed)
    val pluginUninstallFailedMessage = stringResource(R.string.plugin_uninstall_failed)
    val scope = rememberCoroutineScope()
    val extensions by ExtensionHost.extensions.collectAsState()
    val pluginRecords by PluginManager.plugins.collectAsState()
    var installMenu by remember { mutableStateOf(false) }
    var githubDialog by remember { mutableStateOf(false) }
    var githubUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var uninstallId by remember { mutableStateOf<String?>(null) }

    fun runInstall(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching { block() }
                .onFailure { errorMessage = it.message ?: pluginInstallFailedMessage }
            busy = false
        }
    }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runInstall {
            context.contentResolver.openInputStream(uri)?.use { PluginManager.installFromZip(it) }
                ?: error(pluginOpenFailedMessage)
        }
    }

    SettingsSubPage(
        title = stringResource(R.string.extensions),
        onBack = onBack,
        spacing = Space12,
        actions = {
            Box {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                } else {
                    AppIconButton(
                        icon = Lucide.Plus,
                        contentDescription = stringResource(R.string.plugin_install),
                        onClick = { installMenu = true },
                        size = 32.dp,
                        iconSize = 22.dp,
                    )
                }
                DropdownMenu(expanded = installMenu, onDismissRequest = { installMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.plugin_install_github)) },
                        onClick = {
                            installMenu = false
                            githubDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.plugin_install_zip)) },
                        onClick = {
                            installMenu = false
                            zipLauncher.launch(
                                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                            )
                        },
                    )
                }
            }
        },
    ) {
        val records = pluginRecords.associateBy { it.plugin.manifest.id }
        extensions.forEach { ext ->
            ExtensionCard(
                ext = ext,
                plugin = records[ext.info.id],
                onOpenSettings = onOpenSettings,
                onUninstall = { uninstallId = ext.info.id },
            )
        }
    }

    if (githubDialog) {
        AlertDialog(
            onDismissRequest = { githubDialog = false },
            title = { Text(stringResource(R.string.plugin_install_github)) },
            text = {
                OutlinedTextField(
                    value = githubUrl,
                    onValueChange = { githubUrl = it },
                    label = { Text(stringResource(R.string.plugin_github_url)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = githubUrl.isNotBlank(),
                    onClick = {
                        val url = githubUrl.trim()
                        githubDialog = false
                        runInstall { PluginManager.installFromGitHub(url) }
                    },
                ) { Text(stringResource(R.string.plugin_install)) }
            },
            dismissButton = {
                TextButton(onClick = { githubDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    uninstallId?.let { pluginId ->
        val name = extensions.firstOrNull { it.info.id == pluginId }?.info?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { uninstallId = null },
            title = { Text(stringResource(R.string.plugin_uninstall)) },
            text = { Text(stringResource(R.string.plugin_uninstall_confirm_fmt, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        uninstallId = null
                        busy = true
                        scope.launch {
                            runCatching { PluginManager.uninstall(pluginId) }
                                .onFailure { errorMessage = it.message ?: pluginUninstallFailedMessage }
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { uninstallId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text(stringResource(R.string.plugin_operation_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text(stringResource(R.string.confirm)) }
            },
        )
    }
}

@Composable
private fun ExtensionCard(
    ext: Extension,
    plugin: PluginManager.PluginRecord?,
    onOpenSettings: (String) -> Unit,
    onUninstall: () -> Unit,
) {
    val context = LocalContext.current
    val controller = remember(context) { ExtensionSettingsController(AndroidExtensionSettingsDataSource(context)) }
    var enabled by remember(ext.info.id, plugin?.state) {
        mutableStateOf(controller.isEnabled(ext.info.id))
    }
    val pluginFields = remember(plugin?.plugin?.manifest?.configSchema) {
        PluginConfigSchema.parse(plugin?.plugin?.manifest?.configSchema)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Space12),
        verticalArrangement = Arrangement.spacedBy(Space8),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(extName(ext), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(extDesc(ext), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(Space12))
            if (plugin != null) {
                AppIconButton(
                    icon = Lucide.Trash2,
                    contentDescription = stringResource(R.string.plugin_uninstall),
                    onClick = onUninstall,
                    tint = MaterialTheme.colorScheme.error,
                    size = 36.dp,
                    iconSize = 18.dp,
                )
                Spacer(Modifier.width(Space8))
            }
            Switch(
                checked = enabled,
                onCheckedChange = { enabled = controller.setEnabled(ext.info.id, it) },
                enabled = plugin?.state != PluginManager.RuntimeState.INCOMPATIBLE,
            )
        }
        if (plugin != null) {
            Text(
                text = stringResource(R.string.plugin_version_fmt, plugin.plugin.manifest.version),
                style = MaterialTheme.typography.labelSmall,
                color = if (plugin.state == PluginManager.RuntimeState.FAULTED ||
                    plugin.state == PluginManager.RuntimeState.INCOMPATIBLE
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (plugin.lastError.isNotBlank()) {
                Text(
                    text = plugin.lastError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (plugin.state == PluginManager.RuntimeState.INCOMPATIBLE) {
                Text(
                    text = stringResource(R.string.plugin_incompatible),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (ext.info.id in HAS_SETTINGS || pluginFields.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenSettings(ext.info.id) }
                    .padding(vertical = Space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Icon(Lucide.ChevronRight, null, Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun extName(ext: Extension): String = when (ext.info.id) {
    SummarizeExtension.ID -> stringResource(R.string.ext_summarize)
    QuickReplyExtension.ID -> stringResource(R.string.ext_quickreply)
    else -> ext.info.name
}

@Composable
private fun extDesc(ext: Extension): String = when (ext.info.id) {
    SummarizeExtension.ID -> stringResource(R.string.ext_summarize_desc)
    QuickReplyExtension.ID -> stringResource(R.string.ext_quickreply_desc)
    else -> ext.info.description.ifBlank { stringResource(R.string.extension_no_description) }
}

@Composable
private fun PluginSettings(pluginId: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val records by PluginManager.plugins.collectAsState()
    val record = records.firstOrNull { it.plugin.manifest.id == pluginId }
    if (record == null) {
        SettingsSubPage(title = stringResource(R.string.extensions), onBack = onBack) {}
        return
    }
    val controller = remember(context) { ExtensionSettingsController(AndroidExtensionSettingsDataSource(context)) }
    val fields = remember(record.plugin.manifest.configSchema) {
        PluginConfigSchema.parse(record.plugin.manifest.configSchema)
    }
    var configJson by remember(pluginId) { mutableStateOf(controller.config(pluginId)) }
    val config = remember(configJson) {
        runCatching { JSONObject(configJson) }.getOrElse { JSONObject() }
    }

    fun update(key: String, value: Any) {
        val next = runCatching { JSONObject(configJson) }.getOrElse { JSONObject() }
        next.put(key, value)
        configJson = next.toString()
        controller.setConfig(pluginId, configJson)
    }

    SettingsSubPage(title = record.plugin.manifest.name, onBack = onBack, spacing = Space12) {
        fields.forEach { field ->
            when (field) {
                is PluginConfigField.BooleanField -> SwitchRow(
                    label = field.label,
                    checked = if (config.has(field.key)) config.optBoolean(field.key) else field.default,
                    onChange = { update(field.key, it) },
                )
                is PluginConfigField.IntegerField -> NumberRow(
                    label = field.label,
                    value = (if (config.has(field.key)) config.optInt(field.key) else field.default)
                        .coerceTo(field.minimum, field.maximum),
                    onChange = { update(field.key, it.coerceTo(field.minimum, field.maximum)) },
                )
                is PluginConfigField.StringField -> {
                    val value = if (config.has(field.key)) config.optString(field.key) else field.default
                    if (field.options.isEmpty()) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { update(field.key, it.take(PLUGIN_CONFIG_VALUE_CHARS)) },
                            label = { Text(field.label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        PluginEnumRow(
                            label = field.label,
                            value = value.takeIf { it in field.options } ?: field.options.first(),
                            options = field.options,
                            onChange = { update(field.key, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginEnumRow(
    label: String,
    value: String,
    options: List<String>,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space8)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { expanded = true }
                    .padding(horizontal = Space12, vertical = Space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Icon(Lucide.ChevronDown, null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            expanded = false
                            onChange(option)
                        },
                    )
                }
            }
        }
    }
}

private fun Int.coerceTo(minimum: Int?, maximum: Int?): Int {
    var result = this
    if (minimum != null) result = result.coerceAtLeast(minimum)
    if (maximum != null) result = result.coerceAtMost(maximum)
    return result
}

// ── 摘要设置 ──
@Composable
private fun SummarizeSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { ExtensionSettingsController(AndroidExtensionSettingsDataSource(context)) }
    var cfg by remember {
        mutableStateOf(SummarizeExtension.parseConfig(controller.config(SummarizeExtension.ID)))
    }
    fun save(next: SummarizeExtension.Config) {
        cfg = next
        controller.setConfig(SummarizeExtension.ID, SummarizeExtension.encodeConfig(next))
    }
    BackHandler(onBack = onBack)
    SettingsSubPage(stringResource(R.string.ext_summarize), onBack, spacing = Space12) {
        SettingsCard(stringResource(R.string.ext_summarize)) {
            SwitchRow(stringResource(R.string.ext_summarize_auto), cfg.auto) { save(cfg.copy(auto = it)) }
            NumberRow(stringResource(R.string.ext_summarize_keep_recent), cfg.keepRecent) { save(cfg.copy(keepRecent = it.coerceAtLeast(0))) }
            NumberRow(stringResource(R.string.ext_summarize_target_tokens), cfg.targetTokens) { save(cfg.copy(targetTokens = it.coerceAtLeast(1))) }
            NumberRow(stringResource(R.string.ext_summarize_trigger_tokens), cfg.triggerTokens) { save(cfg.copy(triggerTokens = it.coerceAtLeast(1))) }
        }
    }
}

// ── 快捷回复设置（增删改） ──
@Composable
private fun QuickReplySettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { ExtensionSettingsController(AndroidExtensionSettingsDataSource(context)) }
    var items by remember {
        mutableStateOf(QuickReplyExtension.parseConfig(controller.config(QuickReplyExtension.ID)))
    }
    var editIdx by remember { mutableStateOf<Int?>(null) }  // -1 = 新增
    fun persist(next: List<QuickReply>) {
        items = next
        controller.setConfig(QuickReplyExtension.ID, QuickReplyExtension.encodeConfig(next))
    }
    BackHandler(onBack = onBack)
    SettingsSubPage(stringResource(R.string.ext_quickreply), onBack, spacing = Space8) {
        if (items.isEmpty()) {
            Text(stringResource(R.string.ext_qr_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space12))
        }
        items.forEachIndexed { i, qr ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { editIdx = i }
                    .padding(Space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(qr.label.ifBlank { qr.text }, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        (if (qr.send) stringResource(R.string.ext_qr_send) + " · " else "") + qr.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Lucide.X, stringResource(R.string.delete),
                    Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))
                        .clickable { persist(items.filterIndexed { j, _ -> j != i }) },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { editIdx = -1 }
                .padding(Space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space8),
        ) {
            Icon(Lucide.Plus, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.ext_qr_add), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
        }
    }
    val idx = editIdx
    if (idx != null) {
        QuickReplyEditDialog(
            initial = idx.takeIf { it >= 0 }?.let { items[it] },
            onDismiss = { editIdx = null },
            onConfirm = { qr ->
                persist(if (idx < 0) items + qr else items.mapIndexed { j, old -> if (j == idx) qr else old })
                editIdx = null
            },
        )
    }
}

@Composable
private fun QuickReplyEditDialog(initial: QuickReply?, onDismiss: () -> Unit, onConfirm: (QuickReply) -> Unit) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    val text = remember { TextFieldState(initial?.text ?: "") }
    var send by remember { mutableStateOf(initial?.send ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ext_qr_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space8)) {
                OutlinedTextField(label, { label = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ext_qr_label)) })
                AppTextArea(
                    state = text,
                    label = stringResource(R.string.ext_qr_text),
                    minLines = 2, maxLines = 5,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.ext_qr_send), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    Switch(send, { send = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(QuickReply(label = label.trim(), text = text.text.toString().trim(), send = send)) },
                enabled = text.text.isNotBlank() || label.isNotBlank(),
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ── 复用的私有设置行（仿 WorldInfoSettingsScreen） ──
@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space8)) {
        Text(title, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Space8))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(Space12),
            verticalArrangement = Arrangement.spacedBy(Space8),
        ) { content() }
    }
}

@Composable
private fun NumberRow(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Space12))
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t.filter { it.isDigit() }
                onChange(text.toIntOrNull() ?: 0)
            },
            singleLine = true,
            modifier = Modifier.width(96.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
