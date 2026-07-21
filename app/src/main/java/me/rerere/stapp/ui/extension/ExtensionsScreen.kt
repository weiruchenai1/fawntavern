package me.rerere.stapp.ui.extension

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.X
import me.rerere.stapp.R
import me.rerere.stapp.extension.Extension
import me.rerere.stapp.extension.ExtensionHost
import me.rerere.stapp.extension.ExtensionStore
import me.rerere.stapp.extension.QuickReply
import me.rerere.stapp.extension.builtin.QuickReplyExtension
import me.rerere.stapp.extension.builtin.SummarizeExtension
import me.rerere.stapp.ui.components.AppTopBar
import me.rerere.stapp.ui.components.Space8
import me.rerere.stapp.ui.components.Space12
import me.rerere.stapp.ui.components.Space16

/** 有独立设置界面的内置扩展 id */
private val HAS_SETTINGS = setOf(SummarizeExtension.ID, QuickReplyExtension.ID)

/**
 * 扩展管理页：列出已登记扩展、开关启用、进入各自设置。设置页以本文件内嵌套导航呈现
 * （仿 WorldInfoSettingsScreen 挂在世界书列表下），不占用 ChatScreen 的 Screen 枚举。
 */
@Composable
fun ExtensionsScreen(onBack: () -> Unit) {
    var settingsFor by remember { mutableStateOf<String?>(null) }
    when (settingsFor) {
        SummarizeExtension.ID -> SummarizeSettings(onBack = { settingsFor = null })
        QuickReplyExtension.ID -> QuickReplySettings(onBack = { settingsFor = null })
        else -> ExtensionsList(onBack = onBack, onOpenSettings = { settingsFor = it })
    }
}

@Composable
private fun ExtensionsList(onBack: () -> Unit, onOpenSettings: (String) -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(stringResource(R.string.extensions), onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = Space16)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space12),
        ) {
            Spacer(Modifier.height(4.dp))
            ExtensionHost.all().forEach { ext -> ExtensionCard(ext, onOpenSettings) }
            Spacer(Modifier.height(Space16))
        }
    }
}

@Composable
private fun ExtensionCard(ext: Extension, onOpenSettings: (String) -> Unit) {
    val context = LocalContext.current
    var enabled by remember(ext.info.id) { mutableStateOf(ExtensionStore.isEnabled(context, ext.info.id)) }
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
            Switch(enabled, {
                enabled = it
                ExtensionStore.setEnabled(context, ext.info.id, it)
            })
        }
        if (ext.info.id in HAS_SETTINGS) {
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
    else -> ext.info.description.ifBlank { stringResource(R.string.ext_summarize_desc) }
}

// ── 摘要设置 ──
@Composable
private fun SummarizeSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    var cfg by remember {
        mutableStateOf(SummarizeExtension.parseConfig(ExtensionStore.getConfig(context, SummarizeExtension.ID)))
    }
    fun save(next: SummarizeExtension.Config) {
        cfg = next
        ExtensionStore.setConfig(context, SummarizeExtension.ID, SummarizeExtension.encodeConfig(next))
    }
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(stringResource(R.string.ext_summarize), onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = Space16)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space12),
        ) {
            Spacer(Modifier.height(4.dp))
            SettingsCard(stringResource(R.string.ext_summarize)) {
                SwitchRow(stringResource(R.string.ext_summarize_auto), cfg.auto) { save(cfg.copy(auto = it)) }
                NumberRow(stringResource(R.string.ext_summarize_keep_recent), cfg.keepRecent) { save(cfg.copy(keepRecent = it.coerceAtLeast(0))) }
                NumberRow(stringResource(R.string.ext_summarize_target_tokens), cfg.targetTokens) { save(cfg.copy(targetTokens = it.coerceAtLeast(1))) }
                NumberRow(stringResource(R.string.ext_summarize_trigger_tokens), cfg.triggerTokens) { save(cfg.copy(triggerTokens = it.coerceAtLeast(1))) }
            }
            Spacer(Modifier.height(Space16))
        }
    }
}

// ── 快捷回复设置（增删改） ──
@Composable
private fun QuickReplySettings(onBack: () -> Unit) {
    val context = LocalContext.current
    var items by remember {
        mutableStateOf(QuickReplyExtension.parseConfig(ExtensionStore.getConfig(context, QuickReplyExtension.ID)))
    }
    var editIdx by remember { mutableStateOf<Int?>(null) }  // -1 = 新增
    fun persist(next: List<QuickReply>) {
        items = next
        ExtensionStore.setConfig(context, QuickReplyExtension.ID, QuickReplyExtension.encodeConfig(next))
    }
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(stringResource(R.string.ext_quickreply), onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = Space16)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space8),
        ) {
            Spacer(Modifier.height(4.dp))
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
            Spacer(Modifier.height(Space16))
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
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var send by remember { mutableStateOf(initial?.send ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ext_qr_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space8)) {
                OutlinedTextField(label, { label = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ext_qr_label)) })
                OutlinedTextField(text, { text = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                    label = { Text(stringResource(R.string.ext_qr_text)) })
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
                onClick = { onConfirm(QuickReply(label = label.trim(), text = text.trim(), send = send)) },
                enabled = text.isNotBlank() || label.isNotBlank(),
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
