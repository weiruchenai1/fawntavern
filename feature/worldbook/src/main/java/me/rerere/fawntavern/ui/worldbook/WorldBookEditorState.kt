package me.rerere.fawntavern.ui.worldbook

import kotlinx.serialization.Serializable
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.data.worldbook.WorldBookEntry

@Serializable
data class WorldBookEditorState(
    val book: WorldBook,
    val editingEntry: WorldBookEntry? = null,
)
