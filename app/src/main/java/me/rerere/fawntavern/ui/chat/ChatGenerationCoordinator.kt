package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 统一持有单个生成任务的互斥状态与生命周期。 */
internal class ChatGenerationCoordinator(
    private val scope: CoroutineScope,
    private val stopCurrent: () -> Unit,
    private val onRunningChanged: (Boolean) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    var isRunning: Boolean = false
        private set

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

    private fun setRunning(value: Boolean) {
        isRunning = value
        onRunningChanged(value)
    }
}
