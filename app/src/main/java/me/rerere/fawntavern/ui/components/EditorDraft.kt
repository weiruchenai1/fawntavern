package me.rerere.fawntavern.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID
import kotlinx.serialization.KSerializer
import me.rerere.fawntavern.R
import me.rerere.fawntavern.core.diagnostics.SafeLog
import me.rerere.fawntavern.di.LocalAppContainer

@Composable
internal fun <T> rememberEditorDraft(
    kind: String,
    resource: String,
    initial: T,
    serializer: KSerializer<T>,
    onSaved: () -> Unit,
): EditorDraftViewModel<T>? {
    val storage = LocalAppContainer.current.editorDrafts
    val context = LocalContext.current
    val resources = LocalResources.current
    val onSavedState = rememberUpdatedState(onSaved)
    val key = rememberSaveable(kind, resource) { "$kind:${UUID.randomUUID()}" }
    val model: EditorDraftViewModel<T> = viewModel(
        key = "editor:$kind",
        factory = object : ViewModelProvider.Factory {
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
                @Suppress("UNCHECKED_CAST")
                return EditorDraftViewModel(storage, serializer) as VM
            }
        },
    )
    LaunchedEffect(model, key) { model.open(key, initial) }
    LaunchedEffect(model, key, model.completedKey) {
        if (model.completedKey == key) onSavedState.value()
    }
    LaunchedEffect(model, context, resources) {
        model.errors.collect { error ->
            SafeLog.error("EditorDraft", "draft_persistence_failed", error)
            Toast.makeText(context, resources.getString(R.string.editor_draft_failed_fmt, error.message.orEmpty()), Toast.LENGTH_LONG).show()
        }
    }
    return model.takeIf { it.key == key }
}
