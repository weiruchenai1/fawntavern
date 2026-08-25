package me.rerere.fawntavern.ui.character

import android.content.Context
import android.net.Uri
import me.rerere.fawntavern.R
import me.rerere.fawntavern.core.diagnostics.SafeLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.chat.ChatRepository
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.worldbook.WorldBookRepository

internal data class CharacterLibraryState(
    val names: List<String>,
    val cards: Map<String, CharacterCard>,
)

internal interface CharacterLibraryDataSource {
    fun defaultCardName(): String?
    suspend fun names(): List<String>
    suspend fun load(name: String): CharacterCard
    suspend fun create(name: String): CharacterCard
    suspend fun import(uri: Uri): CharacterCard
    suspend fun delete(name: String)
    suspend fun chatCount(name: String): Int = 0
    suspend fun delete(name: String, deleteChats: Boolean, deleteAssociations: Boolean) = delete(name)
    suspend fun saveOrder(names: List<String>)
    suspend fun exportPng(name: String): ByteArray
    suspend fun exportJson(name: String): ByteArray
    fun imageFile(name: String): File
}

internal class AndroidCharacterLibraryDataSource(
    private val context: Context,
) : CharacterLibraryDataSource {
    private suspend fun defaultPresetName(): String =
        PresetRepository.ensureDefaultPreset(context, context.getString(R.string.default_preset))

    override fun defaultCardName(): String? = CharacterRepository.defaultCardName(context)
    override suspend fun names(): List<String> = CharacterRepository.listNames(context)
    override suspend fun load(name: String): CharacterCard = CharacterRepository.load(context, name)
    override suspend fun create(name: String): CharacterCard =
        CharacterRepository.create(context, name, defaultPresetName())
    override suspend fun import(uri: Uri): CharacterCard =
        CharacterRepository.import(context, uri, defaultPresetName())
    override suspend fun delete(name: String) = delete(name, deleteChats = false, deleteAssociations = false)
    override suspend fun chatCount(name: String): Int = ChatRepository.countForCharacter(context, name)
    override suspend fun delete(name: String, deleteChats: Boolean, deleteAssociations: Boolean) {
        val card = if (deleteAssociations) {
            runCatching { CharacterRepository.load(context, name) }.getOrNull()
        } else {
            null
        }
        if (deleteChats) {
            ChatRepository.deleteForCharacter(context, name)
        }
        if (deleteAssociations) {
            // 世界书按名字关联，多张卡可以指同一本：还有别的卡在用的不能跟着这张卡一起删
            val stillReferenced = CharacterRepository.referencedWorldBooks(context, excluding = name)
            card?.let { it.enabledWorldBooks + it.world }
                ?.asSequence()
                ?.filter(String::isNotBlank)
                ?.distinct()
                ?.filter { it !in stillReferenced }
                ?.forEach { WorldBookRepository.delete(context, it) }
            // 内嵌正则不在这里处理：自有正则（linked_regex 指向自己）随卡 JSON 一起消失，
            // 借用别人的（指向另一张卡）属于出借方自己的资产，删借用者不该清空它
        }
        CharacterRepository.delete(context, name)
    }
    override suspend fun saveOrder(names: List<String>) = CharacterRepository.saveOrder(context, names)
    override suspend fun exportPng(name: String): ByteArray = CharacterRepository.exportPngBytes(context, name)
    override suspend fun exportJson(name: String): ByteArray = CharacterRepository.exportJsonBytes(context, name)
    override fun imageFile(name: String): File = CharacterRepository.imageFile(context, name)
}

internal class CharacterLibraryController(
    private val dataSource: CharacterLibraryDataSource,
    private val onLoadError: (String, Throwable) -> Unit = { name, error ->
        SafeLog.warn("CharacterLibrary", "character_card_load_failed", error)
    },
) {
    fun defaultCardName(): String? = dataSource.defaultCardName()

    suspend fun load(): CharacterLibraryState {
        val names = dataSource.names()
        val cards = buildMap {
            names.forEach { name ->
                try {
                    put(name, dataSource.load(name))
                } catch (error: Exception) {
                    onLoadError(name, error)
                }
            }
        }
        return CharacterLibraryState(names, cards)
    }

    suspend fun import(uri: Uri): CharacterCard = dataSource.import(uri)
    suspend fun create(name: String): CharacterCard = dataSource.create(name)
    suspend fun delete(name: String) = dataSource.delete(name)
    suspend fun chatCount(name: String): Int = dataSource.chatCount(name)
    suspend fun delete(name: String, deleteChats: Boolean, deleteAssociations: Boolean) =
        dataSource.delete(name, deleteChats, deleteAssociations)
    suspend fun saveOrder(names: List<String>) = dataSource.saveOrder(names)
    suspend fun exportPng(name: String): ByteArray = dataSource.exportPng(name)
    suspend fun exportJson(name: String): ByteArray = dataSource.exportJson(name)
    fun imageFile(name: String): File = dataSource.imageFile(name)
}

/** Serializes reorder writes and drops snapshots superseded before they reach storage. */
internal class CharacterOrderSaveCoordinator(
    private val scope: CoroutineScope,
    private val save: suspend (List<String>) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val mutex = Mutex()
    private var revision = 0L

    fun request(names: List<String>) {
        val requestedRevision = ++revision
        val snapshot = names.toList()
        scope.launch {
            try {
                mutex.withLock {
                    if (requestedRevision == revision) save(snapshot)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onFailure(error)
            }
        }
    }
}
