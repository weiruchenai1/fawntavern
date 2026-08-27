package me.rerere.fawntavern.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.fawntavern.data.speech.TtsUiState

/** 延迟创建 TTS 控制器并持有聊天界面所需的播放状态。 */
class ChatTtsStateHolder(
    private val controllerFactory: () -> ChatTtsController,
    private val scope: CoroutineScope,
) {
    var speakingTimestamp by mutableStateOf<Long?>(null)
        private set

    var uiState by mutableStateOf(TtsUiState())
        private set

    private val controllerDelegate = lazy {
        controllerFactory().also { controller ->
            scope.launch { controller.ui.collect { uiState = it } }
            scope.launch { controller.speakingTimestamp.collect { speakingTimestamp = it } }
        }
    }
    private val controller by controllerDelegate

    fun speak(timestamp: Long, text: String) = controller.speak(timestamp, text)
    fun stop() = controller.stop()
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun fastForward() = controller.fastForward()
    fun cycleSpeed() = controller.cycleSpeed()

    fun release() {
        if (controllerDelegate.isInitialized()) controller.release()
    }
}
