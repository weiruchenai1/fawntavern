package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 统一持有单个生成任务的互斥状态与生命周期。 */
internal class ChatGenerationCoordinator(
    private val scope: CoroutineScope,
    private val stopCurrent: () -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    var isRunning by mutableStateOf(false)
        private set

    var targetTimestamp by mutableStateOf<Long?>(null)
        private set

    val uiState: ChatUiState.GenerationState
        get() = ChatUiState.GenerationState(
            running = isRunning,
            targetTimestamp = targetTimestamp,
        )

    fun launch(block: suspend () -> Unit): Boolean {
        if (isRunning) return false
        setRunning(true)
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onFailure(error)
            } finally {
                setRunning(false)
            }
        }
        return true
    }

    fun stop() = stopCurrent()

    fun markTarget(timestamp: Long) {
        targetTimestamp = timestamp
    }

    private fun setRunning(value: Boolean) {
        isRunning = value
    }
}
