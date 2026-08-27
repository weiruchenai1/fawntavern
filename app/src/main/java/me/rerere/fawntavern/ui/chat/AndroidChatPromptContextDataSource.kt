package me.rerere.fawntavern.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.core.diagnostics.SafeLog
import me.rerere.fawntavern.data.PromptContextLoader
import me.rerere.fawntavern.data.character.CharRegex
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.preset.toCharRegex
import me.rerere.fawntavern.data.regex.RegexSetRepository

internal class AndroidChatPromptContextDataSource(context: Context) : ChatPromptContextDataSource {
    private val appContext = context.applicationContext

    override suspend fun load(charFile: String): ChatPromptContextSnapshot {
        val loaded = PromptContextLoader.load(appContext, charFile)
        return ChatPromptContextSnapshot(
            loaded = ChatLoadedPromptContext(
                charFile = loaded.charFile,
                card = loaded.card,
                worldBooks = loaded.worldBooks,
                preset = loaded.preset,
                regexSetScripts = loaded.regexSetScripts,
                failures = loaded.failures.map { failure ->
                    ChatPromptLoadFailure(
                        type = ChatPromptContentType.valueOf(failure.type.name),
                        name = failure.name,
                        error = failure.error,
                    )
                },
            ),
            image = if (charFile.isBlank()) null else loadImage(charFile),
        )
    }

    override suspend fun loadCard(charFile: String): CharacterCard? = try {
        CharacterRepository.load(appContext, charFile)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        SafeLog.warn(TAG, "character_card_load_failed", error)
        null
    }

    override suspend fun loadGlobalRegex(): List<CharRegex> =
        RegexSetRepository.loadAll(appContext)
            .filter { it.global }
            .flatMap { set -> set.scripts.map { it.toCharRegex() } }

    override suspend fun ensureDefaultCharacter(
        defaultPresetName: String,
        defaultCharacterName: String,
    ): String {
        val presetName = PresetRepository.ensureDefaultPreset(appContext, defaultPresetName)
        val presetId = PresetRepository.load(appContext, presetName).id
        return CharacterRepository.ensureDefaultCard(appContext, defaultCharacterName, presetId)
    }

    private suspend fun loadImage(charFile: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = CharacterRepository.imageFile(appContext, charFile)
        if (!file.exists()) return@withContext null
        try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (error: Exception) {
            SafeLog.warn(TAG, "character_image_load_failed", error)
            null
        }
    }

    private companion object {
        const val TAG = "ChatPromptContextDataSource"
    }
}
