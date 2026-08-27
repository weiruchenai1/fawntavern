package me.rerere.fawntavern.data

import android.content.Context
import me.rerere.fawntavern.core.diagnostics.SafeLog
import kotlinx.coroutines.CancellationException
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.preset.StPreset
import me.rerere.fawntavern.data.preset.toCharRegex
import me.rerere.fawntavern.data.regex.RegexSetRepository
import me.rerere.fawntavern.data.worldbook.WorldBook
import me.rerere.fawntavern.data.worldbook.WorldBookRepository

/** 一次性加载某个角色生成所需的静态上下文，避免状态分批切换。 */
object PromptContextLoader {
    private const val TAG = "PromptContextLoader"

    enum class ContentType { CHARACTER, WORLD_BOOK, PRESET, REGEX }

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
        val regexSetScripts: List<CharRegex> = emptyList(),
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
            ?: return Loaded(charFile, null, emptyList(), null, failures = failures)
        val books = card.enabledWorldBookIds
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { id ->
                try {
                    WorldBookRepository.loadById(context, id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures += LoadFailure(ContentType.WORLD_BOOK, id, error)
                    SafeLog.error(TAG, "world_book_load_failed", error)
                    null
                }
            }
        val preset = if (card.linkedPresetId.isBlank()) null else try {
            PresetRepository.loadById(context, card.linkedPresetId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failures += LoadFailure(ContentType.PRESET, card.linkedPresetId, error)
            SafeLog.error(TAG, "preset_load_failed", error)
            null
        }
        // 关联的正则集。global=true 的集走全局路径（ChatViewModel 单独加载并对所有聊天生效），
        // 这里跳过，否则两条路径都收一份、同一条脚本会套两遍
        val regexSetScripts = card.enabledRegexIds
            .filter { it.isNotBlank() }
            .distinct()
            .flatMap { setId ->
                try {
                    RegexSetRepository.loadById(context, setId)
                        .takeIf { !it.global }
                        ?.scripts?.map { it.toCharRegex() }
                        .orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures += LoadFailure(ContentType.REGEX, setId, error)
                    SafeLog.error(TAG, "regex_set_load_failed", error)
                    emptyList()
                }
            }
        return Loaded(charFile, card, books, preset, regexSetScripts, failures)
    }
}
