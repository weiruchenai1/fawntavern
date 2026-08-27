package me.rerere.fawntavern.ui.chat

import me.rerere.fawntavern.data.PromptContextLoader

/** Loads and atomically applies the prompt resources for the active character. */
internal class ChatPromptContextCoordinator(
    private val dataSource: ChatPromptContextDataSource,
    private val state: ChatPromptContextStateHolder,
    private val currentCharFile: () -> String,
    private val onLoadFailures: (List<PromptContextLoader.LoadFailure>) -> Unit,
) {
    fun invalidate(): Long = state.invalidate()

    suspend fun refresh(
        charFile: String = currentCharFile(),
        revision: Long = invalidate(),
        includeGlobalRegex: Boolean = false,
    ): Boolean {
        if (includeGlobalRegex) state.replaceGlobalRegex(dataSource.loadGlobalRegex())
        val snapshot = dataSource.load(charFile)
        val failures = state.apply(
            loaded = snapshot.loaded,
            image = snapshot.image,
            expectedRevision = revision,
            currentCharFile = currentCharFile(),
        ) ?: return false
        if (failures.isNotEmpty()) onLoadFailures(failures)
        return true
    }

    suspend fun reloadGlobalRegex() {
        state.replaceGlobalRegex(dataSource.loadGlobalRegex())
    }
}
