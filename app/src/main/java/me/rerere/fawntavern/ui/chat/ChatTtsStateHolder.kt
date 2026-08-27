package me.rerere.fawntavern.ui.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.fawntavern.data.speech.TtsUiState

/** Owns chat TTS playback state while preserving lazy engine initialization. */
internal class ChatTtsStateHolder(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var speakingTimestamp by mutableStateOf<Long?>(null)
        private set

    var uiState by mutableStateOf(TtsUiState())
        private set

    private val controllerDelegate = lazy {
        ChatTtsController(context).also { controller ->
            scope.launch { controller.ui.collect { uiState = it } }
            scope.launch { controller.speakingTs.collect { speakingTimestamp = it } }
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
