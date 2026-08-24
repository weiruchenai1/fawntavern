package me.rerere.fawntavern.ui.preset

import me.rerere.fawntavern.data.preset.PromptItem
import me.rerere.fawntavern.data.preset.RegexScript
import me.rerere.fawntavern.data.preset.StPreset

/** Immutable business state for the preset editor. Dialog visibility is derived from edit targets. */
internal data class PresetEditorState(
    val draft: StPreset,
    val selectedTab: Int = 0,
    val editingPrompt: PromptItem? = null,
    val editingRegex: RegexScript? = null,
    val deletePromptIdentifier: String? = null,
    val deleteRegex: RegexScript? = null,
) {
    val hasOpenModal: Boolean
        get() = editingPrompt != null || editingRegex != null ||
            deletePromptIdentifier != null || deleteRegex != null
}

internal sealed interface PresetEditorAction {
    data class SelectTab(val index: Int) : PresetEditorAction
    data class UpdateDraft(val preset: StPreset) : PresetEditorAction

    data class EditPrompt(val prompt: PromptItem) : PresetEditorAction
    data class SavePrompt(val prompt: PromptItem) : PresetEditorAction
    data object DismissPromptEditor : PresetEditorAction
    data class TogglePrompt(val identifier: String) : PresetEditorAction
    data class ReorderPrompts(val prompts: List<PromptItem>) : PresetEditorAction
    data class RequestPromptDelete(val identifier: String) : PresetEditorAction
    data object ConfirmPromptDelete : PresetEditorAction
    data object DismissPromptDelete : PresetEditorAction

    data class EditRegex(val regex: RegexScript) : PresetEditorAction
    data class CreateRegex(val regex: RegexScript) : PresetEditorAction
    data class SaveRegex(val regex: RegexScript) : PresetEditorAction
    data object DismissRegexEditor : PresetEditorAction
    data class ToggleRegex(val regex: RegexScript) : PresetEditorAction
    data class ImportRegex(val regex: RegexScript) : PresetEditorAction
    data class RequestRegexDelete(val regex: RegexScript) : PresetEditorAction
    data object ConfirmRegexDelete : PresetEditorAction
    data object DismissRegexDelete : PresetEditorAction
}

internal fun reducePresetEditor(
    state: PresetEditorState,
    action: PresetEditorAction,
): PresetEditorState = when (action) {
    is PresetEditorAction.SelectTab -> state.copy(selectedTab = action.index.coerceIn(0, 2))
    is PresetEditorAction.UpdateDraft -> state.copy(draft = action.preset)

    is PresetEditorAction.EditPrompt -> state.copy(
        editingPrompt = action.prompt,
        editingRegex = null,
        deletePromptIdentifier = null,
        deleteRegex = null,
    )
    PresetEditorAction.DismissPromptEditor -> state.copy(editingPrompt = null)
    is PresetEditorAction.SavePrompt -> {
        val prompts = state.draft.prompts.toMutableList()
        val index = prompts.indexOfFirst { it.identifier == action.prompt.identifier }
        if (index >= 0) prompts[index] = action.prompt else prompts += action.prompt
        state.copy(draft = state.draft.copy(prompts = prompts), editingPrompt = null)
    }
    is PresetEditorAction.TogglePrompt -> state.updatePrompt(action.identifier) {
        it.copy(enabled = !it.enabled)
    }
    is PresetEditorAction.ReorderPrompts -> state.copy(
        draft = state.draft.copy(prompts = action.prompts),
    )
    is PresetEditorAction.RequestPromptDelete -> state.copy(
        editingPrompt = null,
        editingRegex = null,
        deleteRegex = null,
        deletePromptIdentifier = action.identifier.takeIf { id ->
            state.draft.prompts.any { it.identifier == id }
        },
    )
    PresetEditorAction.ConfirmPromptDelete -> state.deletePrompt()
    PresetEditorAction.DismissPromptDelete -> state.copy(deletePromptIdentifier = null)

    is PresetEditorAction.EditRegex -> state.copy(
        editingPrompt = null,
        editingRegex = state.draft.regexScripts.firstOrNull { it.matchesRegex(action.regex) },
        deletePromptIdentifier = null,
        deleteRegex = null,
    )
    is PresetEditorAction.CreateRegex -> state.copy(
        editingPrompt = null,
        editingRegex = action.regex,
        deletePromptIdentifier = null,
        deleteRegex = null,
    )
    PresetEditorAction.DismissRegexEditor -> state.copy(editingRegex = null)
    is PresetEditorAction.SaveRegex -> state.saveRegex(action.regex)
    is PresetEditorAction.ToggleRegex -> state.updateRegex(action.regex) {
        it.copy(disabled = !it.disabled)
    }
    is PresetEditorAction.ImportRegex -> state.copy(
        draft = state.draft.copy(regexScripts = state.draft.regexScripts + action.regex),
    )
    is PresetEditorAction.RequestRegexDelete -> state.copy(
        editingPrompt = null,
        editingRegex = null,
        deletePromptIdentifier = null,
        deleteRegex = state.draft.regexScripts.firstOrNull { it.matchesRegex(action.regex) },
    )
    PresetEditorAction.ConfirmRegexDelete -> state.deleteRegex()
    PresetEditorAction.DismissRegexDelete -> state.copy(deleteRegex = null)
}

private inline fun PresetEditorState.updatePrompt(
    identifier: String,
    update: (PromptItem) -> PromptItem,
): PresetEditorState {
    val index = draft.prompts.indexOfFirst { it.identifier == identifier }
    if (index < 0) return this
    val prompts = draft.prompts.toMutableList().also { it[index] = update(it[index]) }
    return copy(draft = draft.copy(prompts = prompts))
}

private inline fun PresetEditorState.updateRegex(
    target: RegexScript?,
    update: (RegexScript) -> RegexScript,
): PresetEditorState {
    val index = target?.let { value -> draft.regexScripts.indexOfFirst { it.matchesRegex(value) } } ?: -1
    if (index < 0) return this
    val scripts = draft.regexScripts.toMutableList().also { it[index] = update(it[index]) }
    return copy(draft = draft.copy(regexScripts = scripts))
}

private fun PresetEditorState.saveRegex(regex: RegexScript): PresetEditorState {
    val target = editingRegex ?: return this
    val index = draft.regexScripts.indexOfFirst { it.matchesRegex(target) }
    val scripts = draft.regexScripts.toMutableList().also {
        if (index >= 0) it[index] = regex else it += regex
    }
    return copy(draft = draft.copy(regexScripts = scripts), editingRegex = null)
}

private fun PresetEditorState.deletePrompt(): PresetEditorState {
    val identifier = deletePromptIdentifier ?: return this
    val index = draft.prompts.indexOfFirst { it.identifier == identifier }
    if (index < 0) return copy(deletePromptIdentifier = null)
    return copy(
        draft = draft.copy(prompts = draft.prompts.toMutableList().also { it.removeAt(index) }),
        deletePromptIdentifier = null,
    )
}

private fun PresetEditorState.deleteRegex(): PresetEditorState {
    val target = deleteRegex ?: return this
    val index = draft.regexScripts.indexOfFirst { it.matchesRegex(target) }
    if (index < 0) return copy(deleteRegex = null)
    return copy(
        draft = draft.copy(regexScripts = draft.regexScripts.toMutableList().also { it.removeAt(index) }),
        deleteRegex = null,
    )
}

private fun RegexScript.matchesRegex(other: RegexScript): Boolean =
    if (id.isNotBlank() && other.id.isNotBlank()) id == other.id else this == other
