package me.rerere.fawntavern.data

import android.content.Context
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.preset.StPreset
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.data.worldbook.WorldBookRepository

/** 一次性加载某个角色生成所需的静态上下文，避免状态分批切换。 */
object PromptContextLoader {
    data class Loaded(
        val charFile: String,
        val card: CharacterCard?,
        val worldBooks: List<WorldBook>,
        val preset: StPreset?,
    )

    suspend fun load(context: Context, charFile: String): Loaded {
        if (charFile.isBlank()) return Loaded(charFile, null, emptyList(), null)
        val card = try {
            CharacterRepository.load(context, charFile)
        } catch (_: Exception) {
            null
        }
            ?: return Loaded(charFile, null, emptyList(), null)
        val books = (card.enabledWorldBooks + card.world)
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { name ->
                try {
                    WorldBookRepository.load(context, name)
                } catch (_: Exception) {
                    null
                }
            }
        val preset = if (card.linkedPreset.isBlank()) null else try {
            PresetRepository.load(context, card.linkedPreset)
        } catch (_: Exception) {
            null
        }
        return Loaded(charFile, card, books, preset)
    }
}
