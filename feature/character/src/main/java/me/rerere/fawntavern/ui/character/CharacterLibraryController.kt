package me.rerere.fawntavern.ui.character

import android.net.Uri
import me.rerere.fawntavern.core.diagnostics.SafeLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.fawntavern.data.character.CharacterCard

data class CharacterLibraryState(
    val names: List<String>,
    val cards: Map<String, CharacterCard>,
)

interface CharacterLibraryDataSource {
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

class CharacterLibraryController(
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

/** 串行保存排序，并丢弃尚未落盘就已过期的快照。 */
class CharacterOrderSaveCoordinator(
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
