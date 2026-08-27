package me.rerere.fawntavern.ui.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.fawntavern.data.settings.TtsStore
import me.rerere.fawntavern.data.speech.TtsEngine
import me.rerere.fawntavern.data.speech.TtsUiState

/** TTS 引擎的 Android 适配器。 */
internal class AndroidChatTtsController(context: Context) : ChatTtsController {
    private val engine = TtsEngine(context) { TtsStore.getSetting(context) }
    override val ui: StateFlow<TtsUiState> = engine.ui

    private val _speakingTs = MutableStateFlow<Long?>(null)
    override val speakingTimestamp: StateFlow<Long?> = _speakingTs.asStateFlow()

    override fun speak(timestamp: Long, text: String) {
        if (_speakingTs.value == timestamp) {
            stop()
            return
        }
        val content = text.trim()
        if (content.isBlank()) return
        stop()
        _speakingTs.value = timestamp
        engine.speak(content) {
            if (_speakingTs.value == timestamp) _speakingTs.value = null
        }
    }

    override fun stop() {
        engine.stop()
        _speakingTs.value = null
    }

    override fun pause() = engine.pause()
    override fun resume() = engine.resume()
    override fun fastForward() = engine.fastForward()

    override fun cycleSpeed() {
        val next = when (engine.ui.value.speed) {
            0.8f -> 1.0f
            1.0f -> 1.2f
            1.2f -> 1.5f
            1.5f -> 0.8f
            else -> 1.0f
        }
        engine.setSpeed(next)
    }

    override fun release() = engine.release()
}
