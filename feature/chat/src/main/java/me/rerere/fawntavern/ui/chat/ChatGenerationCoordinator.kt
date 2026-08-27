package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class ChatGenerationState(
    val running: Boolean,
    val targetTimestamp: Long?,
)

/** 统一持有单个生成任务的交互状态与生命周期。 */
class ChatGenerationCoordinator(
    private val scope: CoroutineScope,
    private val stopCurrent: () -> Unit,
    private val onRunningChanged: (Boolean) -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
) {
    var isRunning by mutableStateOf(false)
        private set

    var targetTimestamp by mutableStateOf<Long?>(null)
        private set

    val state: ChatGenerationState
        get() = ChatGenerationState(
            running = isRunning,
            targetTimestamp = targetTimestamp,
        )

    fun launch(block: suspend () -> Unit): Boolean {
        if (isRunning) return false
        updateRunningState(true)
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onFailure(error)
            } finally {
                targetTimestamp = null
                updateRunningState(false)
            }
        }
        return true
    }

    fun stop() = stopCurrent()

    fun markTarget(timestamp: Long) {
        targetTimestamp = timestamp
    }

    private fun updateRunningState(value: Boolean) {
        isRunning = value
        onRunningChanged(value)
    }
}
