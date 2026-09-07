package me.rerere.fawntavern.ui.character

import kotlinx.serialization.Serializable
import me.rerere.fawntavern.data.character.CharacterCard

@Serializable
data class CharacterEditorState(
    val card: CharacterCard,
    val depthInput: String = (card.depthPrompt?.depth ?: 4).toString(),
    val addingGreeting: Boolean = false,
    val greetingIndex: Int? = null,
    val greetingDraft: String = "",
)
