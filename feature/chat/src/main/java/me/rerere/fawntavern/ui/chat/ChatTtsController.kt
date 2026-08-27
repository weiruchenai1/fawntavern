package me.rerere.fawntavern.ui.chat

import kotlinx.coroutines.flow.StateFlow
import me.rerere.fawntavern.data.speech.TtsUiState

interface ChatTtsController {
    val ui: StateFlow<TtsUiState>
    val speakingTimestamp: StateFlow<Long?>

    fun speak(timestamp: Long, text: String)
    fun stop()
    fun pause()
    fun resume()
    fun fastForward()
    fun cycleSpeed()
    fun release()
}
