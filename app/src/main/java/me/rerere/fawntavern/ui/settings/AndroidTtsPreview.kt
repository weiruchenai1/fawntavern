package me.rerere.fawntavern.ui.settings

import android.content.Context
import me.rerere.fawntavern.data.speech.TTSProviderSetting
import me.rerere.fawntavern.data.speech.TtsEngine

internal class AndroidTtsPreview(
    context: Context,
    setting: () -> TTSProviderSetting,
) : TtsPreview {
    private val engine = TtsEngine(context, setting)

    override fun speak(text: String, onComplete: () -> Unit) = engine.speak(text, onComplete)
    override fun release() = engine.release()
}
