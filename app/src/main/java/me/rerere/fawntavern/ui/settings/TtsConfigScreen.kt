package me.rerere.fawntavern.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PencilLine
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Volume2
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.settings.TtsStore
import me.rerere.fawntavern.data.speech.TTSProviderSetting
import me.rerere.fawntavern.data.speech.TtsEngine
import me.rerere.fawntavern.ui.api.ProviderIcon
import me.rerere.fawntavern.ui.components.AppTopBar
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.SettingsSubPage
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.components.draggableLiftScale
import me.rerere.fawntavern.ui.components.rememberReorderableList
import sh.calvin.reorderable.ReorderableItem

/**
 * 语音服务设置页：已配置提供商的可排序卡片列表 + 添加（右下角 FAB）+ 编辑页。
 * 点卡片选中朗读使用的提供商；长按弹下拉菜单（编辑/删除）。
 */
@Composable
fun TtsConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var services by remember { mutableStateOf(TtsStore.getServices(context)) }
    val configWasRecovered = remember { TtsStore.consumeCorruptionNotice(context) }
    val configRecoveredMessage = stringResource(R.string.tts_config_recovered)
    LaunchedEffect(configWasRecovered) {
        if (configWasRecovered) Toast.makeText(
            context, configRecoveredMessage, Toast.LENGTH_LONG
        ).show()
    }
    var selectedId by remember { mutableStateOf(TtsStore.getSelectedId(context)) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    val stateHolder = rememberSaveableStateHolder()

    if (editingId != null) {
        stateHolder.SaveableStateProvider("detail") {
            val service = services.find { it.id == editingId } ?: return@SaveableStateProvider
            TtsProviderEditScreen(
                service = service,
                onBack = { editingId = null },
                onSave = { updated ->
                    services = services.map { if (it.id == updated.id) updated else it }
                    TtsStore.setServices(context, services)
                },
            )
        }
        return
    }

    if (showAddSheet) {
        AddTtsProviderSheet(
            onDismiss = { showAddSheet = false },
            onAdd = { options ->
                services = services + options
                TtsStore.addService(context, options)
                showAddSheet = false
            },
        )
    }

    stateHolder.SaveableStateProvider("list") {
        BackHandler(onBack = onBack)

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.tts),
                    onBack = onBack,
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showAddSheet = true }) {
                    Icon(Lucide.Plus, stringResource(R.string.tts_add_provider), Modifier.size(24.dp))
                }
            },
        ) { padding ->
            val (listState, reorderState) = rememberReorderableList(
                items = services,
                keyOf = { it.id },
            ) { list ->
                services = list
                TtsStore.setServices(context, list)
            }

            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                state = listState,
                contentPadding = PaddingValues(Space16),
                verticalArrangement = Arrangement.spacedBy(Space12),
            ) {
                item("title") {
                    Text(stringResource(R.string.tts_provider), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                itemsIndexed(services, key = { _, s -> s.id }) { _, provider ->
                    ReorderableItem(reorderState, key = provider.id) { dragging ->
                        TtsProviderCard(
                            provider = provider,
                            selected = provider.id == selectedId,
                            onSelect = {
                                selectedId = provider.id
                                TtsStore.setSelectedId(context, provider.id)
                            },
                            onEdit = { editingId = provider.id },
                            onDelete = {
                                val next = services.filter { it.id != provider.id }
                                services = next
                                TtsStore.setServices(context, next)
                                if (selectedId == provider.id) selectedId = next.first().id
                            },
                            canDelete = services.size > 1,
                            dragging = dragging,
                            handleModifier = Modifier.longPressDraggableHandle(),
                        )
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

/** 语音提供商卡片：图标 + 名称 + 右侧拖拽手柄；点卡片选择（选中高亮），长按弹下拉菜单 */
@Composable
private fun TtsProviderCard(
    provider: TTSProviderSetting,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
    dragging: Boolean,
    handleModifier: Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .draggableLiftScale(dragging)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer)
                .padding(end = Space16),
            horizontalArrangement = Arrangement.spacedBy(Space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f)
                    .combinedClickable(
                        interactionSource = null,
                        indication = null,
                        onClick = onSelect,
                        onLongClick = { showMenu = true },
                    )
                    .padding(start = Space16, top = Space16, bottom = Space16),
                horizontalArrangement = Arrangement.spacedBy(Space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderIcon(provider.displayName, size = 32.dp)
                Text(provider.displayName, style = MaterialTheme.typography.titleSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Lucide.GripVertical, stringResource(R.string.reorder),
                Modifier.size(24.dp).then(handleModifier),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit)) },
                leadingIcon = { Icon(Lucide.PencilLine, null, Modifier.size(18.dp)) },
                onClick = { showMenu = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete),
                    color = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                leadingIcon = { Icon(Lucide.Trash2, null, Modifier.size(18.dp),
                    tint = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                enabled = canDelete,
                onClick = { showMenu = false; onDelete() },
            )
        }
    }
}

/** 添加语音提供商底部面板：先选类型，再按类型填配置，确认后加入列表 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTtsProviderSheet(
    onDismiss: () -> Unit,
    onAdd: (TTSProviderSetting) -> Unit,
) {
    var draft by remember { mutableStateOf(TTSProviderSetting.fromKey(TTSProviderSetting.ALL.first().key)) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding()
                .padding(horizontal = Space16).padding(bottom = Space16),
            verticalArrangement = Arrangement.spacedBy(Space12),
        ) {
            Text(stringResource(R.string.tts_select_provider), style = MaterialTheme.typography.titleMedium)
            // 提供商类型选择框：横向卡片，点选切换草稿类型
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                LazyRow(
                    contentPadding = PaddingValues(vertical = Space8),
                    horizontalArrangement = Arrangement.spacedBy(Space8),
                ) {
                    items(TTSProviderSetting.ALL, key = { it.key }) { preset ->
                        val sel = preset.key == draft.key
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surface)
                                .clickable { draft = TTSProviderSetting.fromKey(preset.key) }
                                .padding(horizontal = Space12, vertical = Space8),
                            horizontalArrangement = Arrangement.spacedBy(Space4),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(preset.displayName, style = MaterialTheme.typography.bodySmall,
                                color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            // 配置字段（随选中类型变化，可滚动）
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space12),
            ) {
                TtsProviderOptionsEditor(draft) { draft = it }
            }
            Button(onClick = { onAdd(draft) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Lucide.Plus, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Space8))
                Text(stringResource(R.string.add))
            }
        }
    }
}

/** 语音提供商编辑页：参数配置（变更即落盘）+ 测试朗读 */
@Composable
private fun TtsProviderEditScreen(
    service: TTSProviderSetting,
    onBack: () -> Unit,
    onSave: (TTSProviderSetting) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    // 测试引擎按当前草稿配置合成，字段变更即时生效
    val draftState = remember(service.id) { mutableStateOf(service) }
    var draft by draftState
    var testing by remember { mutableStateOf(false) }
    val testEngine = remember { TtsEngine(context) { draftState.value } }
    DisposableEffect(Unit) { onDispose { testEngine.release() } }
    BackHandler(onBack = onBack)

    SettingsSubPage(draft.displayName, onBack) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer).padding(Space16),
            verticalArrangement = Arrangement.spacedBy(Space12),
        ) {
            Text(stringResource(R.string.tts_provider_config), style = MaterialTheme.typography.titleMedium)
            TtsProviderOptionsEditor(draft) { updated ->
                draft = updated
                onSave(updated)
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer).padding(Space16),
            verticalArrangement = Arrangement.spacedBy(Space12),
        ) {
            Text(stringResource(R.string.tts_test), style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = {
                    if (testing) return@Button
                    testing = true
                    testEngine.speak(resources.getString(R.string.tts_test_text)) { testing = false }
                },
                enabled = !testing,
            ) {
                Icon(Lucide.Volume2, null, Modifier.size(16.dp))
                Spacer(Modifier.padding(4.dp))
                Text(if (testing) stringResource(R.string.tts_playing) else stringResource(R.string.tts_test))
            }
        }
    }
}

/** 各语音提供商的配置字段（首个为可编辑名称） */
@Composable
private fun TtsProviderOptionsEditor(options: TTSProviderSetting, onUpdate: (TTSProviderSetting) -> Unit) {
    ConfigField(stringResource(R.string.tts_provider_name), options.name) {
        onUpdate(options.withName(it))
    }
    when (options) {
        is TTSProviderSetting.SystemTTS -> {
            SliderLabel(stringResource(R.string.tts_speech_rate), options.speechRate, 0.1f, 3.0f,
                description = stringResource(R.string.tts_speech_rate_desc)) {
                onUpdate(options.copy(speechRate = it))
            }
            SliderLabel(stringResource(R.string.tts_pitch), options.pitch, 0.1f, 2.0f,
                description = stringResource(R.string.tts_pitch_desc)) {
                onUpdate(options.copy(pitch = it))
            }
        }
        is TTSProviderSetting.OpenAI -> OpenAICompatFields(
            options.apiKey, { onUpdate(options.copy(apiKey = it)) },
            options.baseUrl, { onUpdate(options.copy(baseUrl = it)) },
            options.model, { onUpdate(options.copy(model = it)) },
            options.voice, { onUpdate(options.copy(voice = it)) },
        )
        is TTSProviderSetting.Groq -> OpenAICompatFields(
            options.apiKey, { onUpdate(options.copy(apiKey = it)) },
            options.baseUrl, { onUpdate(options.copy(baseUrl = it)) },
            options.model, { onUpdate(options.copy(model = it)) },
            options.voice, { onUpdate(options.copy(voice = it)) },
        )
        is TTSProviderSetting.MiMo -> OpenAICompatFields(
            options.apiKey, { onUpdate(options.copy(apiKey = it)) },
            options.baseUrl, { onUpdate(options.copy(baseUrl = it)) },
            options.model, { onUpdate(options.copy(model = it)) },
            options.voice, { onUpdate(options.copy(voice = it)) },
        )
        is TTSProviderSetting.XAI -> {
            ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
                onUpdate(options.copy(apiKey = it))
            }
            ConfigField(stringResource(R.string.tts_base_url), options.baseUrl) {
                onUpdate(options.copy(baseUrl = it))
            }
            ConfigField(stringResource(R.string.tts_voice_id), options.voiceId) {
                onUpdate(options.copy(voiceId = it))
            }
            ConfigField(stringResource(R.string.tts_language), options.language) {
                onUpdate(options.copy(language = it))
            }
        }
        is TTSProviderSetting.Gemini -> {
            ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
                onUpdate(options.copy(apiKey = it))
            }
            ConfigField(stringResource(R.string.tts_base_url), options.baseUrl) {
                onUpdate(options.copy(baseUrl = it))
            }
            ConfigField(stringResource(R.string.tts_model), options.model) {
                onUpdate(options.copy(model = it))
            }
            ConfigField(stringResource(R.string.tts_voice_name), options.voiceName) {
                onUpdate(options.copy(voiceName = it))
            }
        }
        is TTSProviderSetting.MiniMax -> {
            ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
                onUpdate(options.copy(apiKey = it))
            }
            ConfigField(stringResource(R.string.tts_base_url), options.baseUrl) {
                onUpdate(options.copy(baseUrl = it))
            }
            ConfigField(stringResource(R.string.tts_model), options.model) {
                onUpdate(options.copy(model = it))
            }
            ConfigField(stringResource(R.string.tts_voice_id), options.voiceId) {
                onUpdate(options.copy(voiceId = it))
            }
            ConfigField(stringResource(R.string.tts_emotion), options.emotion) {
                onUpdate(options.copy(emotion = it))
            }
            SliderLabel(stringResource(R.string.tts_speech_rate), options.speed, 0.25f, 4.0f) {
                onUpdate(options.copy(speed = it))
            }
        }
        is TTSProviderSetting.Qwen -> {
            ConfigField(stringResource(R.string.api_key_label), options.apiKey, secret = true) {
                onUpdate(options.copy(apiKey = it))
            }
            ConfigField(stringResource(R.string.tts_base_url), options.baseUrl) {
                onUpdate(options.copy(baseUrl = it))
            }
            ConfigField(stringResource(R.string.tts_model), options.model) {
                onUpdate(options.copy(model = it))
            }
            ConfigField(stringResource(R.string.tts_voice), options.voice) {
                onUpdate(options.copy(voice = it))
            }
            ConfigField(stringResource(R.string.tts_language_type), options.languageType) {
                onUpdate(options.copy(languageType = it))
            }
        }
    }
}

/** OpenAI 兼容提供商共用的 apiKey / baseUrl / model / voice 四字段 */
@Composable
private fun OpenAICompatFields(
    apiKey: String, onApiKey: (String) -> Unit,
    baseUrl: String, onBaseUrl: (String) -> Unit,
    model: String, onModel: (String) -> Unit,
    voice: String, onVoice: (String) -> Unit,
) {
    ConfigField(stringResource(R.string.api_key_label), apiKey, secret = true, onChange = onApiKey)
    ConfigField(stringResource(R.string.tts_base_url), baseUrl, onChange = onBaseUrl)
    ConfigField(stringResource(R.string.tts_model), model, onChange = onModel)
    ConfigField(stringResource(R.string.tts_voice), voice, onChange = onVoice)
}

/** 带标签的滑杆：值变化即时落盘；[description] 非空时在滑杆下方显示说明 */
@Composable
private fun SliderLabel(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    description: String? = null,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space4)) {
        Text("$label（${"%.1f".format(value)}）", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Slider(
            value = value,
            onValueChange = { onChange(it.coerceIn(min, max)) },
            valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
        if (description != null) {
            Text(description, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 配置表单字段：本地暂存 + 变更即落盘；secret 字段默认掩码、尾部眼睛按钮切换可见 */
@Composable
private fun ConfigField(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    // 以 value 为 key：切换提供商类型后外部值变化时，本地暂存同步重置
    var text by remember(value) { mutableStateOf(value) }
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation()
                               else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password)
                          else KeyboardOptions.Default,
        trailingIcon = if (!secret) null else {
            {
                androidx.compose.material3.IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Lucide.EyeOff else Lucide.Eye, null, Modifier.size(20.dp))
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
