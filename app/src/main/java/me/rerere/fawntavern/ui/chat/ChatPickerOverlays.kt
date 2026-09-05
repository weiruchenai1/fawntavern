package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.Composable
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.ui.components.ModelSelectorSheet
import me.rerere.fawntavern.ui.components.ModelSelectorState

/** 只读取模型、搜索和会话选择状态，避免把所有弹层细节留在 ChatContent 主布局中。 */
@Composable
internal fun ChatPickerOverlays(
    state: ChatUiState,
    modelSelector: ModelSelectorState,
    displayProvider: ApiProvider?,
    displayModelId: String,
    showReasoning: Boolean,
    showImageGeneration: Boolean,
    showCharacter: Boolean,
    showSearch: Boolean,
    onDismissReasoning: () -> Unit,
    onDismissImageGeneration: () -> Unit,
    onDismissCharacter: () -> Unit,
    onDismissSearch: () -> Unit,
    onOpenSearchConfig: () -> Unit,
    onAction: (ChatAction) -> Unit,
) {
    ModelSelectorSheet(
        state = modelSelector,
        onSelect = { providerId, modelId -> onAction(ChatAction.SelectModel(providerId, modelId)) },
    )

    if (showReasoning) {
        ReasoningPickerSheet(
            current = state.model.reasoning,
            onSelect = {
                onAction(ChatAction.UpdateReasoning(it))
                onDismissReasoning()
            },
            onDismiss = onDismissReasoning,
        )
    }

    if (showImageGeneration) {
        ImageGenerationSettingsSheet(
            current = state.model.imageGeneration,
            useOpenAiSizes = displayProvider?.type.equals("openai", ignoreCase = true) &&
                displayModelId.startsWith("gpt-image", ignoreCase = true),
            useGradioControls = displayProvider?.type.equals("gradio", ignoreCase = true),
            onChange = { onAction(ChatAction.UpdateImageGeneration(it)) },
            onDismiss = onDismissImageGeneration,
        )
    }

    if (showCharacter) {
        CharacterPickerSheet(
            currentFileName = state.conversation.current?.charFile.orEmpty(),
            onSelect = { fileName, displayName ->
                onAction(ChatAction.OpenCharacter(fileName, displayName))
                onDismissCharacter()
            },
            onDismiss = onDismissCharacter,
        )
    }

    if (showSearch) {
        val search = state.search
        SearchPickerSheet(
            searchEnabled = search.enabled,
            builtInSearchAvailable = search.builtInAvailable,
            builtInSearchEnabled = search.builtInEnabled,
            services = search.services,
            selectedIndex = search.providerIndex.coerceIn(0, search.services.lastIndex.coerceAtLeast(0)),
            onToggleSearch = { onAction(ChatAction.ToggleSearch) },
            onToggleBuiltInSearch = { onAction(ChatAction.ToggleBuiltInSearch) },
            onSelectProvider = { onAction(ChatAction.SelectSearchProvider(it)) },
            onOpenConfig = onOpenSearchConfig,
            onDismiss = onDismissSearch,
        )
    }
}
