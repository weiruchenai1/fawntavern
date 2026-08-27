package me.rerere.fawntavern.ui.chat

/** 加载并原子应用当前角色的提示资源。 */
internal class ChatPromptContextCoordinator(
    private val dataSource: ChatPromptContextDataSource,
    private val state: ChatPromptContextStateHolder,
    private val currentCharFile: () -> String,
    private val onLoadFailures: (List<ChatPromptLoadFailure>) -> Unit,
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
