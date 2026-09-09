package me.rerere.fawntavern.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier

/** Hosts the current page immediately while preserving its saveable state across navigation. */
@Composable
internal fun <T> PageTransition(
    targetState: T,
    modifier: Modifier = Modifier,
    depth: (T) -> Int = { if (it == null) 0 else 1 },
    contentKey: (T) -> Any = { if (it == null) "root" else "page:$it" },
    content: @Composable (T) -> Unit,
) {
    val stateHolder = rememberSaveableStateHolder()
    stateHolder.SaveableStateProvider(contentKey(targetState)) {
        content(targetState)
    }
}
