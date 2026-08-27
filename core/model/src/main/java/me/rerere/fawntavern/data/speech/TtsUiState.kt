package me.rerere.fawntavern.data.speech

/** TTS 朗读的展示状态。 */
data class TtsUiState(
    val speaking: Boolean = false,
    val paused: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1.0f,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 0,
)
