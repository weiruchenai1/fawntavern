package me.rerere.fawntavern.ui.chat

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.fawntavern.data.PromptContextLoader
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.preset.StPreset
import me.rerere.fawntavern.data.preset.toCharRegex
import me.rerere.fawntavern.data.worldbook.WorldBook

/** Owns the character resources used to assemble prompts for the active chat. */
internal class ChatPromptContextStateHolder {
    var card by mutableStateOf<CharacterCard?>(null)
        private set

    var characterImage by mutableStateOf<Bitmap?>(null)
        private set

    var worldBooks by mutableStateOf<List<WorldBook>>(emptyList())
        private set

    var preset by mutableStateOf<StPreset?>(null)
        private set

    private var globalRegex by mutableStateOf<List<CharRegex>>(emptyList())
    private var linkedRegex by mutableStateOf<List<CharRegex>>(emptyList())
    private var loadedCharFile: String? = null
    private var revision = 0L

    val displayRegex: List<CharRegex>
        get() = globalRegex + preset.orEmptyRegex() + linkedRegex

    fun invalidate(): Long {
        loadedCharFile = null
        return ++revision
    }

    fun isLoadedFor(charFile: String): Boolean = loadedCharFile == charFile

    fun setCard(value: CharacterCard?) {
        card = value
    }

    fun setGlobalRegex(values: List<CharRegex>) {
        globalRegex = values
    }

    fun apply(
        loaded: PromptContextLoader.Loaded,
        image: Bitmap?,
        expectedRevision: Long,
        currentCharFile: String,
    ): List<PromptContextLoader.LoadFailure>? {
        if (revision != expectedRevision || currentCharFile != loaded.charFile) return null
        card = loaded.card
        characterImage = image
        worldBooks = loaded.worldBooks
        preset = loaded.preset
        linkedRegex = loaded.regexSetScripts
        loadedCharFile = loaded.charFile
        return loaded.failures
    }

    private fun StPreset?.orEmptyRegex(): List<CharRegex> =
        this?.regexScripts?.map { it.toCharRegex() }.orEmpty()
}
