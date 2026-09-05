package me.rerere.fawntavern.ui.settings

import me.rerere.fawntavern.data.settings.DefaultModelRole

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Bolt
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquareText
import com.composables.icons.lucide.RotateCcw
import me.rerere.fawntavern.R
import me.rerere.fawntavern.di.LocalAppContainer
import me.rerere.fawntavern.ui.api.ProviderIcon
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.AppTextArea
import me.rerere.fawntavern.ui.components.ModelSelectorSheet
import me.rerere.fawntavern.ui.components.rememberModelSelectorState
import me.rerere.fawntavern.ui.components.SettingsSubPage
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space8

/**
 * 默认模型设置页：每个角色独立选择模型（聊天 / 标题 / 摘要），
 * 未选时回落角色记忆或全局默认；标题与摘要另支持自定义提示词。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultModelPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = LocalAppContainer.current.features.defaultModels
    var state by remember(controller) { mutableStateOf(controller.load()) }
    val apiConfig = state.apiConfig

    var pickingRole by remember { mutableStateOf<DefaultModelRole?>(null) }
    var promptRole by remember { mutableStateOf<DefaultModelRole?>(null) }

    // 三张卡片共用同一个模型选择器；pickingRole 决定当前在给哪个角色选模型
    val pickCurrent = pickingRole?.let { state.entry(it).model } ?: ""
    val modelSelector = rememberModelSelectorState(pickCurrent, apiConfig.providers)

    val chatEntry = state.entry(DefaultModelRole.CHAT)
    val titleEntry = state.entry(DefaultModelRole.TITLE)
    val summaryEntry = state.entry(DefaultModelRole.SUMMARY)
    val translationEntry = state.entry(DefaultModelRole.TRANSLATION)

    val useCurrentLabel = stringResource(R.string.default_model_use_global)

    /** 将 "providerId::modelId" 解析为 (模型ID图标key, 显示名) */
    fun resolveModel(spec: String): Pair<String, String> {
        if (spec.isBlank()) return "" to ""
        val provId = spec.substringBefore("::")
        val modelId = spec.substringAfter("::", "")
        val prov = apiConfig.providers.find { it.id == provId && it.enabled }
        val model = prov?.models?.find { it.id == modelId }
        return modelId to (model?.name ?: modelId.ifBlank { spec })
    }

    /**
     * 已选角色模型则显示所选，否则显示"使用当前对话模型"。
     * 三张卡片统一使用此逻辑。
     */
    fun roleModelParts(entry: DefaultModelEntry): Pair<String, String> {
        val spec = entry.model.takeIf { it.isNotBlank() }
        if (spec != null) return resolveModel(spec)
        return "" to useCurrentLabel
    }

    BackHandler(onBack = onBack)

    SettingsSubPage(stringResource(R.string.default_model_title), onBack) {
        val (chIcon, chName) = roleModelParts(chatEntry)
        ModelCard(
            icon = Lucide.MessageSquareText,
            title = stringResource(R.string.default_model_chat),
            subtitle = stringResource(R.string.default_model_chat_desc),
            iconKey = chIcon,
            displayName = chName,
            showReset = chatEntry.model.isNotBlank(),
            showBolt = false,
            onPick = { pickingRole = DefaultModelRole.CHAT; modelSelector.open() },
            onReset = { state = controller.reset(state, DefaultModelRole.CHAT) },
        )

        val (tiIcon, tiName) = roleModelParts(titleEntry)
        ModelCard(
            icon = Lucide.FileText,
            title = stringResource(R.string.default_model_title_role),
            subtitle = stringResource(R.string.default_model_title_desc),
            iconKey = tiIcon,
            displayName = tiName,
            showReset = titleEntry.model.isNotBlank(),
            showBolt = true,
            onPick = { pickingRole = DefaultModelRole.TITLE; modelSelector.open() },
            onReset = { state = controller.reset(state, DefaultModelRole.TITLE) },
            onConfig = { promptRole = DefaultModelRole.TITLE },
        )

        val (suIcon, suName) = roleModelParts(summaryEntry)
        ModelCard(
            icon = Lucide.Bot,
            title = stringResource(R.string.default_model_summary),
            subtitle = stringResource(R.string.default_model_summary_desc),
            iconKey = suIcon,
            displayName = suName,
            showReset = summaryEntry.model.isNotBlank(),
            showBolt = true,
            onPick = { pickingRole = DefaultModelRole.SUMMARY; modelSelector.open() },
            onReset = { state = controller.reset(state, DefaultModelRole.SUMMARY) },
            onConfig = { promptRole = DefaultModelRole.SUMMARY },
        )

        val (trIcon, trName) = roleModelParts(translationEntry)
        ModelCard(
            icon = Lucide.Languages,
            title = stringResource(R.string.default_model_translation),
            subtitle = stringResource(R.string.default_model_translation_desc),
            iconKey = trIcon,
            displayName = trName,
            showReset = translationEntry.model.isNotBlank(),
            showBolt = true,
            onPick = { pickingRole = DefaultModelRole.TRANSLATION; modelSelector.open() },
            onReset = { state = controller.reset(state, DefaultModelRole.TRANSLATION) },
            onConfig = { promptRole = DefaultModelRole.TRANSLATION },
        )
    }

    // ── 模型选择器（三张卡片共用） ──
    val pickRole = pickingRole
    if (pickRole != null) {
        ModelSelectorSheet(
            state = modelSelector,
            onSelect = { providerId, modelId ->
                state = controller.setModel(state, pickRole, "$providerId::$modelId")
                pickingRole = null
            },
            onDismiss = { pickingRole = null },
        )
    }

    // ── 提示词弹窗（标题 / 摘要） ──
    val prRole = promptRole
    if (prRole != null) {
        PromptSheet(
            role = prRole,
            currentPrompt = state.entry(prRole).prompt,
            defaultPrompt = controller.defaultPrompt(prRole),
            onSave = { prompt ->
                state = controller.setPrompt(state, prRole, prompt)
                promptRole = null
            },
            onDismiss = { promptRole = null },
        )
    }
}

// ── 卡片组件 ──

@Composable
internal fun ModelCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    iconKey: String,
    selectionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    displayName: String,
    showReset: Boolean,
    showBolt: Boolean,
    onPick: () -> Unit,
    onReset: () -> Unit,
    onConfig: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space8)) {
            Icon(icon, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface)
            Text(title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showReset) {
                AppIconButton(
                    icon = Lucide.RotateCcw,
                    contentDescription = stringResource(R.string.default_model_reset_model),
                    onClick = onReset,
                    size = 28.dp,
                    iconSize = 16.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showBolt && onConfig != null) {
                AppIconButton(
                    icon = Lucide.Bolt,
                    contentDescription = stringResource(R.string.default_model_prompt_config),
                    onClick = onConfig,
                    size = 28.dp,
                    iconSize = 18.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3, overflow = TextOverflow.Ellipsis)

        Spacer(Modifier.height(4.dp))

        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable { onPick() }
                .padding(horizontal = Space12, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space8),
        ) {
            if (iconKey.isNotBlank()) {
                ProviderIcon(name = iconKey, size = 24.dp)
            } else if (selectionIcon != null) {
                Icon(
                    selectionIcon,
                    null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Lucide.ChevronRight, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── 提示词底部弹出 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptSheet(
    role: DefaultModelRole,
    currentPrompt: String,
    defaultPrompt: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (role) {
        DefaultModelRole.TITLE -> stringResource(R.string.default_model_prompt_title)
        DefaultModelRole.SUMMARY -> stringResource(R.string.default_model_prompt_summary)
        DefaultModelRole.TRANSLATION -> stringResource(R.string.default_model_prompt_translation)
        DefaultModelRole.CHAT -> error("Chat role does not have a prompt editor")
    }
    val hint = when (role) {
        DefaultModelRole.TITLE -> stringResource(R.string.default_model_prompt_title_hint)
        DefaultModelRole.SUMMARY -> stringResource(R.string.default_model_prompt_summary_hint)
        DefaultModelRole.TRANSLATION -> stringResource(R.string.default_model_prompt_translation_hint)
        DefaultModelRole.CHAT -> error("Chat role does not have a prompt editor")
    }
    val text = rememberTextFieldState(currentPrompt.ifBlank { defaultPrompt })
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier.fillMaxWidth()
                .imePadding()
                .padding(horizontal = Space16)
                .padding(bottom = Space16),
            verticalArrangement = Arrangement.spacedBy(Space12),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface)

            AppTextArea(
                state = text,
                placeholder = hint,
                minLines = 6, maxLines = 15,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { text.setTextAndPlaceCursorAtEnd(defaultPrompt) }) {
                    Icon(Lucide.RotateCcw, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(Space4))
                    Text(stringResource(R.string.default_model_reset_prompt))
                }
                TextButton(onClick = {
                    val trimmed = text.text.toString().trim()
                    onSave(if (trimmed == defaultPrompt) "" else trimmed)
                }) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}
