package me.rerere.fawntavern.ui.translator

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ClipboardPaste
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Eraser
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Package
import com.composables.icons.lucide.Square
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.ui.api.ProviderIcon
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.AppTopBar
import me.rerere.fawntavern.ui.components.ModelSelectorSheet
import me.rerere.fawntavern.ui.components.PickerRow
import me.rerere.fawntavern.ui.components.rememberModelSelectorState

private data class TranslationLanguage(
    val tag: String,
    val labelRes: Int,
    val promptName: String,
)

private val translationLanguages = listOf(
    TranslationLanguage("zh-CN", R.string.language_simplified_chinese, "Simplified Chinese"),
    TranslationLanguage("en", R.string.language_english, "English"),
    TranslationLanguage("zh-TW", R.string.language_traditional_chinese, "Traditional Chinese"),
    TranslationLanguage("ja", R.string.language_japanese, "Japanese"),
    TranslationLanguage("ko", R.string.language_korean, "Korean"),
    TranslationLanguage("fr", R.string.language_french, "French"),
    TranslationLanguage("de", R.string.language_german, "German"),
    TranslationLanguage("it", R.string.language_italian, "Italian"),
    TranslationLanguage("es-ES", R.string.language_spanish, "Spanish"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(
    onBack: () -> Unit,
    apiConfig: ApiConfig,
    fallbackModelSpec: String,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val controller = remember(context) { TranslatorController(AndroidTranslatorDataSource(context)) }
    val defaults = remember(controller, fallbackModelSpec) { controller.defaults(fallbackModelSpec) }
    var selectedModelSpec by rememberSaveable {
        mutableStateOf(defaults.modelSpec)
    }
    val translationPrompt = defaults.prompt
    var selectedLanguageTag by rememberSaveable { mutableStateOf(translationLanguages.first().tag) }
    val selectedLanguage = translationLanguages.firstOrNull { it.tag == selectedLanguageTag }
        ?: translationLanguages.first()
    val inputText = rememberTextFieldState()
    var translatedText by rememberSaveable { mutableStateOf("") }
    var showLanguagePicker by rememberSaveable { mutableStateOf(false) }
    var translating by remember { mutableStateOf(false) }
    var translationJob by remember { mutableStateOf<Job?>(null) }
    val stopFlag = remember { AtomicBoolean(false) }
    val modelSelector = rememberModelSelectorState(selectedModelSpec, apiConfig.providers)

    fun stopTranslation() {
        stopFlag.set(true)
        translationJob?.cancel()
        translationJob = null
        translating = false
    }

    fun startTranslation() {
        val sourceText = inputText.text.toString()
        if (sourceText.isBlank()) {
            Toast.makeText(context, resources.getString(R.string.translator_input_required), Toast.LENGTH_SHORT).show()
            return
        }
        val providerId = selectedModelSpec.substringBefore("::")
        val modelId = selectedModelSpec.substringAfter("::", "")
        val provider = apiConfig.providers.firstOrNull {
            it.id == providerId && it.enabled && it.model(modelId) != null
        }
        if (provider == null || modelId.isBlank()) {
            Toast.makeText(context, resources.getString(R.string.select_model_first), Toast.LENGTH_SHORT).show()
            modelSelector.open()
            return
        }

        stopTranslation()
        stopFlag.set(false)
        translatedText = ""
        translating = true
        translationJob = scope.launch {
            try {
                controller.translate(
                    provider = provider,
                    modelId = modelId,
                    sourceText = sourceText,
                    language = selectedLanguage.promptName,
                    prompt = translationPrompt,
                    isCancelled = stopFlag::get,
                    onUpdate = { translatedText = it },
                )
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    resources.getString(R.string.translator_failed, error.message.orEmpty()),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                translating = false
                translationJob = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopTranslation() }
    }
    BackHandler {
        stopTranslation()
        onBack()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            AppTopBar(stringResource(R.string.translator_title), onBack) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppIconButton(
                        icon = Lucide.Eraser,
                        contentDescription = stringResource(R.string.clear),
                        onClick = {
                            inputText.setTextAndPlaceCursorAtEnd("")
                            translatedText = ""
                        },
                        enabled = inputText.text.isNotEmpty() || translatedText.isNotEmpty(),
                        size = 32.dp,
                        iconSize = 24.dp,
                    )
                    Box(
                        Modifier.size(32.dp).clip(CircleShape)
                            .clickable { modelSelector.open() },
                        contentAlignment = Alignment.Center,
                    ) {
                        val modelId = selectedModelSpec.substringAfter("::", "")
                        if (modelId.isBlank()) {
                            Icon(Lucide.Package, stringResource(R.string.select_model), Modifier.size(24.dp))
                        } else {
                            ProviderIcon(modelId, size = 24.dp)
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { showLanguagePicker = true },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(selectedLanguage.labelRes),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Lucide.ChevronDown,
                                stringResource(R.string.select_translation_language),
                                Modifier.size(20.dp),
                            )
                        }
                    }
                    Button(
                        onClick = { if (translating) stopTranslation() else startTranslation() },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (translating) {
                            Icon(Lucide.Square, null, Modifier.size(20.dp))
                        } else {
                            Icon(Lucide.Languages, null, Modifier.size(20.dp))
                        }
                        Text(
                            stringResource(if (translating) R.string.stop else R.string.translate),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TranslationTextBox(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                state = inputText,
                placeholder = stringResource(R.string.translator_input_placeholder),
                action = {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                clipboard.getClipEntry()?.clipData?.let { clip ->
                                    if (clip.itemCount > 0) {
                                        inputText.setTextAndPlaceCursorAtEnd(
                                            clip.getItemAt(0).coerceToText(context).toString()
                                        )
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Lucide.ClipboardPaste, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.paste_text), Modifier.padding(start = 6.dp))
                    }
                },
            )

            TranslationResultBox(
                modifier = Modifier.fillMaxWidth().weight(1f),
                text = translatedText,
                placeholder = stringResource(R.string.translator_result_placeholder),
                action = {
                    FilledTonalButton(
                        enabled = translatedText.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("translation", translatedText))
                                )
                            }
                            Toast.makeText(context, resources.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(Lucide.Copy, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.copy_translation), Modifier.padding(start = 6.dp))
                    }
                },
            )
        }
    }

    ModelSelectorSheet(
        state = modelSelector,
        onSelect = { providerId, modelId ->
            selectedModelSpec = "$providerId::$modelId"
            controller.saveModel(selectedModelSpec)
        },
    )

    if (showLanguagePicker) {
        ModalBottomSheet(onDismissRequest = { showLanguagePicker = false }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.select_translation_language),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                translationLanguages.forEach { language ->
                    val selected = language.tag == selectedLanguageTag
                    PickerRow(
                        selected = selected,
                        onClick = {
                            selectedLanguageTag = language.tag
                            showLanguagePicker = false
                        },
                        icon = {},
                        label = { Text(stringResource(language.labelRes)) },
                        trailing = {
                            if (selected) Icon(Lucide.Check, null, Modifier.size(18.dp))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TranslationTextBox(
    modifier: Modifier = Modifier,
    state: TextFieldState,
    placeholder: String,
    action: @Composable () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    Box(
        modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(16.dp),
    ) {
        BasicTextField(
            state = state,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
            modifier = Modifier.matchParentSize().focusRequester(focusRequester)
                .padding(bottom = 52.dp),
            decorator = TextFieldDecorator { inner ->
                Box {
                    if (state.text.isEmpty()) {
                        Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
            },
        )
        Box(Modifier.align(Alignment.BottomEnd)) { action() }
    }
}

@Composable
private fun TranslationResultBox(
    modifier: Modifier = Modifier,
    text: String,
    placeholder: String,
    action: @Composable () -> Unit,
) {
    Box(
        modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
            .padding(16.dp),
    ) {
        Box(
            Modifier.matchParentSize().padding(bottom = 52.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text.ifBlank { placeholder },
                style = MaterialTheme.typography.bodyLarge,
                color = if (text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.align(Alignment.BottomEnd)) { action() }
    }
}
