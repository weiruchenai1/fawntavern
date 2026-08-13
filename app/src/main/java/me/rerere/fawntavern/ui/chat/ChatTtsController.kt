package me.rerere.fawntavern.ui.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.fawntavern.data.settings.TtsStore
import me.rerere.fawntavern.data.speech.TtsEngine
import me.rerere.fawntavern.data.speech.TtsUiState

/** Chat screen TTS interaction coordinator. */
internal class ChatTtsController(context: Context) {
    private val engine = TtsEngine(context) { TtsStore.getSetting(context) }
    val ui: StateFlow<TtsUiState> = engine.ui

    private val _speakingTs = MutableStateFlow<Long?>(null)
    val speakingTs: StateFlow<Long?> = _speakingTs.asStateFlow()

    fun speak(ts: Long, text: String) {
        if (_speakingTs.value == ts) {
            stop()
            return
        }
        val content = text.trim()
        if (content.isBlank()) return
        stop()
        _speakingTs.value = ts
        engine.speak(content) {
            if (_speakingTs.value == ts) _speakingTs.value = null
        }
    }

    fun stop() {
        engine.stop()
        _speakingTs.value = null
    }

    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun fastForward() = engine.fastForward()

    fun cycleSpeed() {
        val next = when (engine.ui.value.speed) {
            0.8f -> 1.0f
            1.0f -> 1.2f
            1.2f -> 1.5f
            1.5f -> 0.8f
            else -> 1.0f
        }
        engine.setSpeed(next)
    }

    fun release() = engine.release()
}
