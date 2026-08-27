package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 加载用户资料并同步刷新依赖资料的提示资源。 */
internal class ChatProfileCoordinator(
    private val scope: CoroutineScope,
    private val profile: ChatProfileStateHolder,
    private val promptContext: ChatPromptContextCoordinator,
) {
    fun reload() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { profile.load() }
            profile.apply(loaded)
        }
    }

    fun update(name: String, description: String) {
        profile.save(name, description)
        reload()
        scope.launch { promptContext.reloadGlobalRegex() }
    }
}
