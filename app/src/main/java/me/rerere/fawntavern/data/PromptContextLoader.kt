package me.rerere.fawntavern.data

import android.content.Context
import me.rerere.fawntavern.core.diagnostics.SafeLog
import kotlinx.coroutines.CancellationException
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.preset.StPreset
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.data.worldbook.WorldBookRepository

/** 一次性加载某个角色生成所需的静态上下文，避免状态分批切换。 */
object PromptContextLoader {
    private const val TAG = "PromptContextLoader"

    enum class ContentType { CHARACTER, WORLD_BOOK, PRESET }

    data class LoadFailure(
        val type: ContentType,
        val name: String,
        val error: Exception,
    )

    data class Loaded(
        val charFile: String,
        val card: CharacterCard?,
        val worldBooks: List<WorldBook>,
        val preset: StPreset?,
        val failures: List<LoadFailure> = emptyList(),
    )

    suspend fun load(context: Context, charFile: String): Loaded {
        if (charFile.isBlank()) return Loaded(charFile, null, emptyList(), null)
        val failures = mutableListOf<LoadFailure>()
        val card = try {
            CharacterRepository.load(context, charFile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failures += LoadFailure(ContentType.CHARACTER, charFile, error)
            SafeLog.error(TAG, "character_card_load_failed", error)
            null
        }
            ?: return Loaded(charFile, null, emptyList(), null, failures)
        val books = (card.enabledWorldBooks + card.world)
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { name ->
                try {
                    WorldBookRepository.load(context, name)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures += LoadFailure(ContentType.WORLD_BOOK, name, error)
                    SafeLog.error(TAG, "world_book_load_failed", error)
                    null
                }
            }
        val preset = if (card.linkedPreset.isBlank()) null else try {
            PresetRepository.load(context, card.linkedPreset)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failures += LoadFailure(ContentType.PRESET, card.linkedPreset, error)
            SafeLog.error(TAG, "preset_load_failed", error)
            null
        }
        return Loaded(charFile, card, books, preset, failures)
    }
}
