package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 数据管理操作后重新对齐聊天与提示上下文状态。 */
internal class ChatDataRefreshCoordinator(
    private val scope: CoroutineScope,
    private val refreshUseCase: RefreshChatDataUseCase,
    private val conversation: ChatConversationStateHolder,
    private val promptState: ChatPromptContextStateHolder,
    private val promptContext: ChatPromptContextCoordinator,
) {
    fun refresh(defaultPresetName: String, defaultCharacterName: String) {
        val revision = promptContext.invalidate()
        scope.launch {
            val result = refreshUseCase(
                currentSession = conversation.current,
                defaultPresetName = defaultPresetName,
                defaultCharacterName = defaultCharacterName,
            )
            conversation.replaceSessions(result.summaries)
            if (result.replaceCard) promptState.replaceCard(result.resolvedCard)
            if (result.replaceCurrent) conversation.replaceCurrent(result.currentSession)
            promptContext.refresh(
                revision = revision,
                includeGlobalRegex = true,
            )
        }
    }
}
