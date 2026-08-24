package me.rerere.fawntavern.ui.preset

import me.rerere.fawntavern.data.preset.PromptItem
import me.rerere.fawntavern.data.preset.RegexScript
import me.rerere.fawntavern.data.preset.StPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetEditorStateTest {
    private val prompt = PromptItem(identifier = "p1", name = "Prompt")
    private val regex = RegexScript(id = "r1", scriptName = "Regex")

    @Test
    fun promptActionsUpdateDraftAndCloseEditor() {
        val initial = PresetEditorState(StPreset(prompts = listOf(prompt)))
        val editing = reducePresetEditor(initial, PresetEditorAction.EditPrompt(prompt))
        val saved = reducePresetEditor(
            editing,
            PresetEditorAction.SavePrompt(prompt.copy(name = "Updated")),
        )

        assertEquals("Updated", saved.draft.prompts.single().name)
        assertNull(saved.editingPrompt)
    }

    @Test
    fun deleteConfirmationUsesStableIndexAndClearsTarget() {
        val initial = PresetEditorState(StPreset(regexScripts = listOf(regex)))
        val requested = reducePresetEditor(initial, PresetEditorAction.RequestRegexDelete(regex))
        val deleted = reducePresetEditor(requested, PresetEditorAction.ConfirmRegexDelete)

        assertEquals(emptyList<RegexScript>(), deleted.draft.regexScripts)
        assertNull(deleted.deleteRegex)
    }

    @Test
    fun invalidIndexesAreIgnored() {
        val initial = PresetEditorState(StPreset(prompts = listOf(prompt)))
        val next = reducePresetEditor(initial, PresetEditorAction.TogglePrompt("missing"))

        assertEquals(initial, next)
    }

    @Test
    fun draftRemainsIndependentFromARefreshedSourcePreset() {
        val initial = PresetEditorState(StPreset(name = "demo", prompts = listOf(prompt)))
        val edited = reducePresetEditor(
            initial,
            PresetEditorAction.SavePrompt(prompt.copy(name = "Local draft")),
        )
        val refreshedSource = StPreset(name = "demo", prompts = listOf(prompt.copy(name = "Repository value")))

        // The screen's remember(preset.name) key intentionally does not rebuild `edited` here.
        assertEquals("Local draft", edited.draft.prompts.single().name)
        assertEquals("Repository value", refreshedSource.prompts.single().name)
    }

    @Test
    fun openingAnotherEditorClosesThePreviousModal() {
        val initial = PresetEditorState(
            StPreset(prompts = listOf(prompt), regexScripts = listOf(regex)),
        )
        val promptEditing = reducePresetEditor(initial, PresetEditorAction.EditPrompt(prompt))
        val regexEditing = reducePresetEditor(promptEditing, PresetEditorAction.EditRegex(regex))

        assertNull(regexEditing.editingPrompt)
        assertEquals(regex, regexEditing.editingRegex)
        assertEquals(true, regexEditing.hasOpenModal)
    }

    @Test
    fun deleteConfirmationCountsAsOpenModal() {
        val initial = PresetEditorState(PresetEditorStateTestPreset.promptOnly)
        val requested = reducePresetEditor(initial, PresetEditorAction.RequestPromptDelete("delete-me"))

        assertEquals(true, requested.hasOpenModal)
    }

    private object PresetEditorStateTestPreset {
        val promptOnly = StPreset(prompts = listOf(PromptItem(identifier = "delete-me")))
    }

    @Test
    fun deleteUsesIdentifierAfterReorder() {
        val first = prompt.copy(identifier = "first")
        val second = prompt.copy(identifier = "second")
        val initial = PresetEditorState(StPreset(prompts = listOf(first, second)))
        val reordered = reducePresetEditor(initial, PresetEditorAction.ReorderPrompts(listOf(second, first)))
        val requested = reducePresetEditor(reordered, PresetEditorAction.RequestPromptDelete("first"))
        val deleted = reducePresetEditor(requested, PresetEditorAction.ConfirmPromptDelete)

        assertEquals(listOf("second"), deleted.draft.prompts.map { it.identifier })
    }

    @Test
    fun regexWithStableIdMatchesEvenWhenItsEditableFieldsChanged() {
        val original = regex.copy(findRegex = "old")
        val editedInDraft = regex.copy(findRegex = "new")
        val state = PresetEditorState(StPreset(regexScripts = listOf(editedInDraft)))

        val editing = reducePresetEditor(state, PresetEditorAction.EditRegex(original))

        assertEquals(editedInDraft, editing.editingRegex)
    }

    @Test
    fun newRegexIsAddedOnlyAfterSavingTheEditor() {
        val initial = PresetEditorState(StPreset())
        val editing = reducePresetEditor(initial, PresetEditorAction.CreateRegex(regex))
        val dismissed = reducePresetEditor(editing, PresetEditorAction.DismissRegexEditor)
        val saved = reducePresetEditor(editing, PresetEditorAction.SaveRegex(regex.copy(scriptName = "Saved")))

        assertEquals(emptyList<RegexScript>(), dismissed.draft.regexScripts)
        assertEquals("Saved", saved.draft.regexScripts.single().scriptName)
        assertNull(saved.editingRegex)
    }
}
