package me.rerere.fawntavern.ui.chat

import android.content.Context
import androidx.paging.PagingData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatRepository

private const val CHAT_PAGE_SIZE = 60

@OptIn(ExperimentalCoroutinesApi::class)
internal fun chatPagingSource(
    context: Context,
    sessionIds: Flow<String?>,
): Flow<PagingData<ChatMessage>> = sessionIds.flatMapLatest { id ->
    if (id == null) {
        flowOf(PagingData.empty())
    } else {
        flow {
            emit(PagingData.empty())
            val count = ChatRepository.messageCount(context, id)
            val initialKey = (count - CHAT_PAGE_SIZE).takeIf { it > 0 }
            emitAll(ChatRepository.messagesPaged(context, id, initialKey))
        }
    }
}
