package me.rerere.fawntavern.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.domain.RegexEngine
import me.rerere.fawntavern.ui.components.noRippleClickable

internal data class TextCopyPreview(
    val applyDisplayTransforms: Boolean = true,
    val regexScripts: List<CharRegex> = emptyList(),
    val depth: Int? = null,
    val userName: String = "",
    val charName: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextCopySheet(
    title: String,
    text: String,
    onCopyAll: (String) -> Unit,
    onSaveAsTxt: ((String) -> Unit)? = null,
    onDismiss: (String) -> Unit,
    editable: Boolean = false,
    preview: TextCopyPreview? = null,
    lineBatchSize: Int? = null,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val editState = if (editable) remember(text) { TextFieldState(text) } else null
    var actualView by rememberSaveable { mutableStateOf(false) }
    val actualText = remember(text, preview) {
        preview?.let {
            if (it.applyDisplayTransforms) {
                RegexEngine.applyForDisplay(
                    content = text,
                    scripts = it.regexScripts,
                    depth = it.depth,
                    userName = it.userName,
                    charName = it.charName,
                )
            } else {
                text
            }
        }
    }
    val displayedText = if (actualView && actualText != null) actualText else text
    val effectiveBatchSize = lineBatchSize?.takeIf { it > 0 }
    var visibleLineCount by remember(displayedText, actualView, effectiveBatchSize) {
        mutableIntStateOf(effectiveBatchSize ?: Int.MAX_VALUE)
    }
    var hasMoreVisualLines by remember(displayedText, actualView, effectiveBatchSize) {
        mutableStateOf(false)
    }
    val currentText = {
        when {
            editState != null -> editState.text.toString()
            actualView && actualText != null -> actualText
            else -> text
        }
    }

    ModalBottomSheet(
        onDismissRequest = { onDismiss(currentText()) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                if (onSaveAsTxt != null) {
                    TextButton(onClick = { onSaveAsTxt(currentText()) }) {
                        Icon(
                            Lucide.FileText,
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.save_as_txt))
                    }
                }
                TextButton(onClick = { onCopyAll(currentText()) }) {
                    Icon(
                        Lucide.Copy,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.copy_all))
                }
            }
            if (preview != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextCopyViewToggle(actual = actualView, onChange = { actualView = it })
                }
            }
            if (editState != null) {
                BasicTextField(
                    state = editState,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 24.dp),
                )
            } else if (effectiveBatchSize != null) {
                SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
                    Column(
                        Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp),
                    ) {
                        Text(
                            text = displayedText,
                            style = if (actualView && actualText != null && preview != null) {
                                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = visibleLineCount,
                            overflow = TextOverflow.Clip,
                            onTextLayout = { hasMoreVisualLines = it.hasVisualOverflow },
                        )
                        if (hasMoreVisualLines) {
                            TextButton(
                                onClick = {
                                    visibleLineCount += effectiveBatchSize
                                },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            ) {
                                Text(stringResource(R.string.show_more_lines))
                            }
                        }
                    }
                }
            } else if (actualView && actualText != null && preview != null) {
                SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
                    Text(
                        text = actualText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp),
                    )
                }
            } else {
                SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TextCopyViewToggle(actual: Boolean, onChange: (Boolean) -> Unit) {
    @Composable
    fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                .noRippleClickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }

    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(2.dp),
    ) {
        Segment(stringResource(R.string.copy_view_original), !actual) { onChange(false) }
        Segment(stringResource(R.string.copy_view_actual), actual) { onChange(true) }
    }
}
