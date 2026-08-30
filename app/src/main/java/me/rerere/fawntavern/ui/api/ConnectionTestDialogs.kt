package me.rerere.fawntavern.ui.api

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import kotlinx.coroutines.launch
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ConnectionTester
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.ModelSelectorSheet
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.components.rememberModelSelectorState

/** 单项连接测试的状态 */
private sealed interface TestState {
    data object Idle : TestState
    data object Loading : TestState
    data class Ok(val text: String) : TestState
    data class Err(val message: String) : TestState
}

private enum class ConnectionTestKind { NON_STREAMING, STREAMING, TOOL_CALL }

/**
 * 连接测试对话框：选一个模型，对其并发跑 非流式 / 流式 / 工具调用 三项测试，
 * 各自独立显示进度与结果（成功显示回复文本，失败显示错误信息）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionTestDialog(prov: ApiProvider, onDismiss: () -> Unit) {
    if (prov.type == "gradio") {
        GradioConnectionTestDialog(prov, onDismiss)
        return
    }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    var selectedModel by remember(prov.id) { mutableStateOf(prov.models.firstOrNull()?.id ?: "") }
    // filterEnabled=false —— 提供商还没启用时也要能先测通
    // currentModel 须带 "providerId::" 前缀，行内才按此匹配选中态
    val modelSelector = rememberModelSelectorState("${prov.id}::$selectedModel", listOf(prov), filterEnabled = false)
    var nonStreaming by remember { mutableStateOf<TestState>(TestState.Idle) }
    var streaming by remember { mutableStateOf<TestState>(TestState.Idle) }
    var streamingText by remember { mutableStateOf("") }
    var toolCall by remember { mutableStateOf<TestState>(TestState.Idle) }
    var detailKind by remember { mutableStateOf<ConnectionTestKind?>(null) }

    val running = nonStreaming is TestState.Loading ||
        streaming is TestState.Loading || toolCall is TestState.Loading

    fun runTests() {
        if (selectedModel.isBlank()) return
        nonStreaming = TestState.Loading
        streaming = TestState.Loading
        streamingText = ""
        toolCall = TestState.Loading
        scope.launch {
            nonStreaming = runCatching { TestState.Ok(ConnectionTester.testNonStreaming(prov, selectedModel)) }
                .getOrElse { TestState.Err(it.message ?: it.toString()) }
        }
        scope.launch {
            streaming = runCatching {
                ConnectionTester.testStreaming(prov, selectedModel) { streamingText += it }
                TestState.Ok(streamingText)
            }.getOrElse { TestState.Err(it.message ?: it.toString()) }
        }
        scope.launch {
            toolCall = runCatching {
                val result = ConnectionTester.testToolCall(prov, selectedModel)
                TestState.Ok(
                    if (result.toolName.isNotBlank())
                        resources.getString(R.string.test_tool_called_fmt, result.toolName, result.args)
                    else resources.getString(R.string.test_tool_not_called_fmt, result.text)
                )
            }.getOrElse { TestState.Err(it.message ?: it.toString()) }
        }
    }

    // 模型选择底板：从屏幕底部滑出，点击模型后自动关闭并把选中模型带回弹窗
    ModelSelectorSheet(
        state = modelSelector,
        onSelect = { _, modelId ->
            selectedModel = modelId
            nonStreaming = TestState.Idle
            streaming = TestState.Idle
            streamingText = ""
            toolCall = TestState.Idle
            detailKind = null
        },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.test_connection)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space12)) {
                // 已选模型：点击可重新打开底板换模型
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { modelSelector.open() }
                        .padding(horizontal = Space12, vertical = Space8),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space8),
                ) {
                    ProviderIcon(selectedModel.ifBlank { prov.name }, size = 24.dp)
                    Text(
                        text = selectedModel.ifBlank { stringResource(R.string.select_model_hint) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedModel.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Lucide.ChevronDown, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TestResultRow(
                    stringResource(R.string.test_non_streaming),
                    nonStreaming,
                    onClick = { detailKind = ConnectionTestKind.NON_STREAMING },
                )
                TestResultRow(
                    stringResource(R.string.test_streaming),
                    streaming,
                    liveText = streamingText,
                    onClick = { detailKind = ConnectionTestKind.STREAMING },
                )
                TestResultRow(
                    stringResource(R.string.test_tool_call),
                    toolCall,
                    onClick = { detailKind = ConnectionTestKind.TOOL_CALL },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { runTests() }, enabled = !running && selectedModel.isNotBlank()) {
                Text(if (running) stringResource(R.string.testing) else stringResource(R.string.start_test))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )

    detailKind?.let { kind ->
        val label = when (kind) {
            ConnectionTestKind.NON_STREAMING -> stringResource(R.string.test_non_streaming)
            ConnectionTestKind.STREAMING -> stringResource(R.string.test_streaming)
            ConnectionTestKind.TOOL_CALL -> stringResource(R.string.test_tool_call)
        }
        val state = when (kind) {
            ConnectionTestKind.NON_STREAMING -> nonStreaming
            ConnectionTestKind.STREAMING -> streaming
            ConnectionTestKind.TOOL_CALL -> toolCall
        }
        ConnectionTestDetailSheet(
            label = label,
            modelId = selectedModel,
            state = state,
            liveText = if (kind == ConnectionTestKind.STREAMING) streamingText else "",
            onDismiss = { detailKind = null },
        )
    }
}

@Composable
private fun GradioConnectionTestDialog(prov: ApiProvider, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember(prov.id) { mutableStateOf<TestState>(TestState.Idle) }

    fun inspect() {
        state = TestState.Loading
        scope.launch {
            state = runCatching {
                TestState.Ok(ConnectionTester.testNonStreaming(prov, prov.models.firstOrNull()?.id.orEmpty()))
            }.getOrElse { TestState.Err(it.message ?: it.toString()) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.test_connection)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space12)) {
                Text(
                    stringResource(R.string.gradio_endpoint_check_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TestResultRow(
                    label = stringResource(R.string.gradio_endpoint_label),
                    state = state,
                    onClick = {},
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { inspect() }, enabled = state !is TestState.Loading) {
                Text(if (state is TestState.Loading) stringResource(R.string.testing)
                    else stringResource(R.string.start_test))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun TestResultRow(
    label: String,
    state: TestState,
    liveText: String = "",
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = state !is TestState.Idle, onClick = onClick)
            .padding(vertical = Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space8),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
        when (state) {
            TestState.Idle -> Text("—", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TestState.Loading -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (liveText.isNotBlank()) {
                    Text(liveText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            is TestState.Ok -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
                Text("✓", style = MaterialTheme.typography.titleMedium, color = successGreen())
                val shown = liveText.ifBlank { state.text }
                if (shown.isNotBlank()) {
                    Text(shown, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            is TestState.Err -> Text(state.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f),
                maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        if (state !is TestState.Idle) {
            Icon(
                Lucide.ChevronRight,
                contentDescription = stringResource(R.string.test_view_details),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionTestDetailSheet(
    label: String,
    modelId: String,
    state: TestState,
    liveText: String,
    onDismiss: () -> Unit,
) {
    val status = when (state) {
        TestState.Idle -> stringResource(R.string.test_status_waiting)
        TestState.Loading -> stringResource(R.string.testing)
        is TestState.Ok -> stringResource(R.string.test_status_success)
        is TestState.Err -> stringResource(R.string.test_status_failed)
    }
    val detail = when (state) {
        TestState.Idle -> ""
        TestState.Loading -> liveText
        is TestState.Ok -> liveText.ifBlank { state.text }
        is TestState.Err -> listOf(state.message, liveText)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.7f)
                .padding(horizontal = Space16)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space12),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.test_detail_title, label),
                    style = MaterialTheme.typography.titleMedium,
                )
                AppIconButton(
                    icon = Lucide.X,
                    contentDescription = stringResource(R.string.close),
                    onClick = onDismiss,
                )
            }
            DetailField(stringResource(R.string.test_detail_model), modelId)
            DetailField(stringResource(R.string.test_detail_status), status)
            Text(
                stringResource(R.string.test_detail_content),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
                Text(
                    detail.ifBlank { stringResource(R.string.test_detail_empty) },
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(Space12),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is TestState.Err) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(Space8))
        }
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space12),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun successGreen(): Color =
    if (isSystemInDarkTheme()) Color(0xFF86EFAC) else Color(0xFF166534)
