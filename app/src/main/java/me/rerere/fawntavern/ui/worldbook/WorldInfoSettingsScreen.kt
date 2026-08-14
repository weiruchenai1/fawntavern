package me.rerere.fawntavern.ui.worldbook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.worldbook.WorldInfoSettings
import me.rerere.fawntavern.ui.components.SettingsSubPage
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12

/** 全局世界书激活设置（对齐 SillyTavern #wiActivationSettings，无插入策略/全局书概念）。改动即时落盘。 */
@Composable
fun WorldInfoSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) {
        WorldInfoSettingsController(AndroidWorldInfoSettingsDataSource(context))
    }
    var s by remember(controller) { mutableStateOf(controller.load()) }
    fun save(next: WorldInfoSettings) {
        s = controller.update(next)
    }

    BackHandler(onBack = onBack)
    SettingsSubPage(stringResource(R.string.wi_activation_settings), onBack, spacing = Space12) {
        SettingsCard(stringResource(R.string.wi_settings_scan_budget)) {
            NumberRow(stringResource(R.string.wi_scan_depth), s.scanDepth) { save(s.copy(scanDepth = it.coerceAtLeast(0))) }
            NumberRow(stringResource(R.string.wi_context_percent), s.budgetPercent) { save(s.copy(budgetPercent = it.coerceIn(0, 100))) }
            NumberRow(stringResource(R.string.wi_budget_cap), s.budgetCap, stringResource(R.string.wi_zero_unlimited)) { save(s.copy(budgetCap = it.coerceAtLeast(0))) }
            NumberRow(stringResource(R.string.wi_min_activations), s.minActivations, stringResource(R.string.wi_zero_unlimited)) { save(s.copy(minActivations = it.coerceAtLeast(0))) }
            NumberRow(stringResource(R.string.wi_min_act_depth_max), s.minActivationsDepthMax, stringResource(R.string.wi_zero_unlimited)) { save(s.copy(minActivationsDepthMax = it.coerceAtLeast(0))) }
            NumberRow(stringResource(R.string.wi_max_recursion), s.maxRecursionSteps, stringResource(R.string.wi_zero_unlimited)) { save(s.copy(maxRecursionSteps = it.coerceAtLeast(0))) }
        }
        SettingsCard(stringResource(R.string.wi_settings_matching)) {
            SwitchRow(stringResource(R.string.wi_include_names), s.includeNames) { save(s.copy(includeNames = it)) }
            SwitchRow(stringResource(R.string.wi_recursive), s.recursive) { save(s.copy(recursive = it)) }
            SwitchRow(stringResource(R.string.wi_case_sensitive), s.caseSensitive) { save(s.copy(caseSensitive = it)) }
            SwitchRow(stringResource(R.string.wi_match_whole_words), s.matchWholeWords) { save(s.copy(matchWholeWords = it)) }
            SwitchRow(stringResource(R.string.wi_use_group_scoring), s.useGroupScoring) { save(s.copy(useGroupScoring = it)) }
            SwitchRow(stringResource(R.string.wi_overflow_alert), s.overflowAlert) { save(s.copy(overflowAlert = it)) }
        }
    }
}

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

/** 数值行：左标签(+副标)，右侧紧凑数字输入。空串按 0 处理。 */
@Composable
private fun NumberRow(label: String, value: Int, hint: String? = null, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
