package me.rerere.fawntavern.ui.settings

interface TtsPreview {
    fun speak(text: String, onComplete: () -> Unit)
    fun release()
}
