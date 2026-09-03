package me.rerere.fawntavern.ui.settings

import android.content.Context
import me.rerere.fawntavern.data.settings.TtsStore
import me.rerere.fawntavern.data.speech.TTSProviderSetting

internal class AndroidTtsConfigDataSource(
    private val context: Context,
) : TtsConfigDataSource {
    override fun services(): List<TTSProviderSetting> = TtsStore.getServices(context)
    override fun saveServices(services: List<TTSProviderSetting>) = TtsStore.setServices(context, services)
    override fun selectedId(): String = TtsStore.getSelectedId(context)
    override fun saveSelectedId(id: String) = TtsStore.setSelectedId(context, id)
    override fun consumeRecoveryNotice(): Boolean = TtsStore.consumeCorruptionNotice(context)
}
