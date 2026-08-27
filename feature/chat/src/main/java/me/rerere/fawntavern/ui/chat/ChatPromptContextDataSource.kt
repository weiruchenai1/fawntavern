package me.rerere.fawntavern.ui.chat

import android.graphics.Bitmap
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.preset.StPreset
import me.rerere.fawntavern.data.worldbook.WorldBook

enum class ChatPromptContentType { CHARACTER, WORLD_BOOK, PRESET, REGEX }

data class ChatPromptLoadFailure(
    val type: ChatPromptContentType,
    val name: String,
    val error: Exception,
)

data class ChatLoadedPromptContext(
    val charFile: String,
    val card: CharacterCard?,
    val worldBooks: List<WorldBook>,
    val preset: StPreset?,
    val regexSetScripts: List<CharRegex> = emptyList(),
    val failures: List<ChatPromptLoadFailure> = emptyList(),
)

data class ChatPromptContextSnapshot(
    val loaded: ChatLoadedPromptContext,
    val image: Bitmap?,
)

interface ChatPromptContextDataSource {
    suspend fun load(charFile: String): ChatPromptContextSnapshot
    suspend fun loadCard(charFile: String): CharacterCard?
    suspend fun loadGlobalRegex(): List<CharRegex>
    suspend fun ensureDefaultCharacter(defaultPresetName: String, defaultCharacterName: String): String
}
