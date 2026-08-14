package me.rerere.fawntavern.ui.character

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.character.CharacterRepository

internal data class CharacterLibraryState(
    val names: List<String>,
    val cards: Map<String, CharacterCard>,
)

internal interface CharacterLibraryDataSource {
    fun defaultCardName(): String?
    suspend fun names(): List<String>
    suspend fun load(name: String): CharacterCard
    suspend fun import(uri: Uri): CharacterCard
    suspend fun delete(name: String)
    suspend fun saveOrder(names: List<String>)
    suspend fun exportPng(name: String): ByteArray
    suspend fun exportJson(name: String): ByteArray
    fun imageFile(name: String): File
}

internal class AndroidCharacterLibraryDataSource(
    private val context: Context,
) : CharacterLibraryDataSource {
    override fun defaultCardName(): String? = CharacterRepository.defaultCardName(context)
    override suspend fun names(): List<String> = CharacterRepository.listNames(context)
    override suspend fun load(name: String): CharacterCard = CharacterRepository.load(context, name)
    override suspend fun import(uri: Uri): CharacterCard = CharacterRepository.import(context, uri)
    override suspend fun delete(name: String) = CharacterRepository.delete(context, name)
    override suspend fun saveOrder(names: List<String>) = CharacterRepository.saveOrder(context, names)
    override suspend fun exportPng(name: String): ByteArray = CharacterRepository.exportPngBytes(context, name)
    override suspend fun exportJson(name: String): ByteArray = CharacterRepository.exportJsonBytes(context, name)
    override fun imageFile(name: String): File = CharacterRepository.imageFile(context, name)
}

internal class CharacterLibraryController(
    private val dataSource: CharacterLibraryDataSource,
    private val onLoadError: (String, Throwable) -> Unit = { name, error ->
        Log.w("CharacterLibrary", "无法加载角色卡: $name", error)
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
    suspend fun delete(name: String) = dataSource.delete(name)
    suspend fun saveOrder(names: List<String>) = dataSource.saveOrder(names)
    suspend fun exportPng(name: String): ByteArray = dataSource.exportPng(name)
    suspend fun exportJson(name: String): ByteArray = dataSource.exportJson(name)
    fun imageFile(name: String): File = dataSource.imageFile(name)
}
