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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.domain.RegexEngine
import me.rerere.fawntavern.ui.components.noRippleClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class TextCopyPreview(
    val applyDisplayTransforms: Boolean = true,
    val regexScripts: List<CharRegex> = emptyList(),
    val depth: Int? = null,
    val userName: String = "",
    val charName: String = "",
)

private const val DefaultReadOnlyBatchLines = 100
private const val AutoBatchThresholdChars = 32_000
private const val MaxCharsPerBatch = 4_000

private data class RenderedTextWindow(
    val text: String,
    val ranges: List<IntRange>,
)

private data class RenderedTextBlock(
    val sourceStart: Int,
    val window: RenderedTextWindow,
    val range: IntRange,
)

/** Finds a bounded prefix without materializing lineSequence()/split() lists for a huge string. */
internal fun textWindowEnd(
    text: String,
    maxLines: Int,
    maxChars: Int,
    start: Int = 0,
): Int {
    val safeStart = start.coerceIn(0, text.length)
    if (safeStart == text.length || maxLines <= 0 || maxChars <= 0) return safeStart
    val charEnd = (safeStart.toLong() + maxChars).coerceAtMost(text.length.toLong()).toInt()
    var lines = 1
    var index = safeStart
    while (index < charEnd) {
        if (text[index] == '\n' && ++lines > maxLines) return index + 1
        index++
    }
    return charEnd
}

internal fun textWindowRanges(
    text: String,
    batchCount: Int,
    linesPerBatch: Int,
    charsPerBatch: Int,
): List<IntRange> = buildList {
    var start = 0
    for (batch in 0 until batchCount.coerceAtLeast(0)) {
        if (start >= text.length) break
        val end = textWindowEnd(text, linesPerBatch, charsPerBatch, start)
        if (end <= start) break
        add(start until end)
        start = end
    }
}

internal fun allTextWindowRanges(
    text: String,
    linesPerBatch: Int,
    charsPerBatch: Int,
): List<IntRange> = buildList {
    var start = 0
    while (start < text.length) {
        val end = textWindowEnd(text, linesPerBatch, charsPerBatch, start)
        if (end <= start) break
        add(start until end)
        start = end
    }
}

internal fun shouldLoadNextTextBatch(
    lastVisibleIndex: Int,
    totalItems: Int,
    hasMoreText: Boolean,
    loading: Boolean,
): Boolean = hasMoreText && !loading &&
    (totalItems == 0 || lastVisibleIndex >= totalItems - 1)

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
    val effectiveBatchSize = lineBatchSize?.takeIf { it > 0 }
        ?: DefaultReadOnlyBatchLines.takeIf {
            !editable && (preview != null || text.length > AutoBatchThresholdChars)
        }
    var visibleBatchCount by remember(text, actualView, effectiveBatchSize) {
        mutableIntStateOf(1)
    }
    val visibleRanges = remember(text, visibleBatchCount, effectiveBatchSize) {
        if (effectiveBatchSize == null) emptyList() else textWindowRanges(
            text = text,
            batchCount = visibleBatchCount,
            linesPerBatch = effectiveBatchSize,
            charsPerBatch = MaxCharsPerBatch,
        )
    }
    val displayedText = remember(text, actualView, preview, effectiveBatchSize) {
        if (!editable && effectiveBatchSize == null && actualView &&
            preview?.applyDisplayTransforms == true) {
            RegexEngine.applyForDisplay(
                content = text,
                scripts = preview.regexScripts,
                depth = preview.depth,
                userName = preview.userName,
                charName = preview.charName,
            )
        } else {
            text
        }
    }
    val visibleEnd = visibleRanges.lastOrNull()?.let { it.last + 1 } ?: 0
    val hasMoreText = visibleEnd < text.length
    val coroutineScope = rememberCoroutineScope()
    var resolvingFullText by remember { mutableStateOf(false) }
    var renderedActualWindows by remember(text, preview) {
        mutableStateOf<Map<Int, RenderedTextWindow>>(emptyMap())
    }
    val transformVisibleChunks = actualView && preview?.applyDisplayTransforms == true

    androidx.compose.runtime.LaunchedEffect(transformVisibleChunks, visibleRanges, preview) {
        if (!transformVisibleChunks || preview == null) return@LaunchedEffect
        val missing = visibleRanges.filter { it.first !in renderedActualWindows }
        if (missing.isEmpty()) return@LaunchedEffect
        val generated = withContext(Dispatchers.Default) {
            missing.associate { range ->
                val source = text.substring(range.first, range.last + 1)
                val transformed = runCatching {
                    RegexEngine.applyForDisplay(
                        content = source,
                        scripts = preview.regexScripts,
                        depth = preview.depth,
                        userName = preview.userName,
                        charName = preview.charName,
                    )
                }.getOrDefault(source)
                range.first to RenderedTextWindow(
                    text = transformed,
                    ranges = allTextWindowRanges(
                        text = transformed,
                        linesPerBatch = effectiveBatchSize ?: DefaultReadOnlyBatchLines,
                        charsPerBatch = MaxCharsPerBatch,
                    ),
                )
            }
        }
        renderedActualWindows = renderedActualWindows + generated
    }
    val renderedActualBlocks = remember(visibleRanges, renderedActualWindows) {
        buildList {
            visibleRanges.forEach { sourceRange ->
                val window = renderedActualWindows[sourceRange.first] ?: return@forEach
                window.ranges.forEach { outputRange ->
                    add(RenderedTextBlock(sourceRange.first, window, outputRange))
                }
            }
        }
    }
    val actualChunksLoading = transformVisibleChunks &&
        visibleRanges.any { it.first !in renderedActualWindows }
    val batchListState = rememberLazyListState()

    androidx.compose.runtime.LaunchedEffect(text, actualView) {
        batchListState.scrollToItem(0)
    }

    androidx.compose.runtime.LaunchedEffect(
        batchListState,
        hasMoreText,
        actualChunksLoading,
        visibleBatchCount,
        actualView,
    ) {
        if (!hasMoreText || actualChunksLoading) return@LaunchedEffect
        snapshotFlow {
            val layout = batchListState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to layout.totalItemsCount
        }.collect { (lastVisible, totalItems) ->
            if (shouldLoadNextTextBatch(
                    lastVisibleIndex = lastVisible,
                    totalItems = totalItems,
                    hasMoreText = hasMoreText,
                    loading = actualChunksLoading,
                )) {
                if (visibleBatchCount < Int.MAX_VALUE) visibleBatchCount++
            }
        }
    }

    fun fullText(onReady: (String) -> Unit) {
        val edited = editState
        val transform = preview?.takeIf { actualView && it.applyDisplayTransforms }
        when {
            edited != null -> onReady(edited.text.toString())
            transform == null -> onReady(text)
            resolvingFullText -> Unit
            else -> coroutineScope.launch {
                resolvingFullText = true
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        RegexEngine.applyForDisplay(
                            content = text,
                            scripts = transform.regexScripts,
                            depth = transform.depth,
                            userName = transform.userName,
                            charName = transform.charName,
                        )
                    }.getOrDefault(text)
                }
                resolvingFullText = false
                onReady(result)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { onDismiss(editState?.text?.toString() ?: text) },
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
                    TextButton(
                        enabled = !resolvingFullText,
                        onClick = { fullText(onSaveAsTxt) },
                    ) {
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
                TextButton(
                    enabled = !resolvingFullText,
                    onClick = { fullText(onCopyAll) },
                ) {
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
                    LazyColumn(
                        Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        state = batchListState,
                    ) {
                        if (transformVisibleChunks) {
                            items(
                                renderedActualBlocks,
                                key = { "actual:${it.sourceStart}:${it.range.first}" },
                            ) { block ->
                                val displayedChunk = remember(block) {
                                    block.window.text.substring(block.range.first, block.range.last + 1)
                                        .let { chunk ->
                                            if (chunk.endsWith('\n') &&
                                                block.range.last + 1 < block.window.text.length) {
                                                chunk.dropLast(1)
                                            } else {
                                                chunk
                                            }
                                        }
                                }
                                Text(
                                    text = displayedChunk,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            items(visibleRanges, key = { "source:${it.first}" }) { range ->
                                val sourceChunk = remember(text, range) {
                                    text.substring(range.first, range.last + 1).let { chunk ->
                                        if (chunk.endsWith('\n') && range.last + 1 < text.length) {
                                            chunk.dropLast(1)
                                        } else {
                                            chunk
                                        }
                                    }
                                }
                                Text(
                                    text = sourceChunk,
                                    style = if (actualView && preview != null) {
                                        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                                    } else {
                                        MaterialTheme.typography.bodyMedium
                                    },
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        if (actualChunksLoading) {
                            item(key = "actual_loading") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                }
                            }
                        }
                        if (hasMoreText && !actualChunksLoading) {
                            item(key = "load_more_sentinel:$visibleBatchCount") {
                                Spacer(Modifier.size(1.dp))
                            }
                        }
                    }
                }
            } else if (actualView && preview != null) {
                SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
                    Text(
                        text = displayedText,
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
