package me.rerere.fawntavern.ui.chat

import androidx.paging.PagingData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.domain.chat.ChatDataRepository

private const val CHAT_PAGE_SIZE = 60

@OptIn(ExperimentalCoroutinesApi::class)
internal fun chatPagingSource(
    repository: ChatDataRepository,
    sessionIds: Flow<String?>,
): Flow<PagingData<ChatMessage>> = sessionIds.flatMapLatest { id ->
    if (id == null) {
        flowOf(PagingData.empty())
    } else {
        flow {
            emit(PagingData.empty())
            val count = repository.messageCount(id)
            val initialKey = (count - CHAT_PAGE_SIZE).takeIf { it > 0 }
            emitAll(repository.messagesPaged(id, initialKey))
        }
    }
}
