package me.rerere.fawntavern.ui.character

import android.content.Context
import android.net.Uri
import java.io.File
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiConfigStore
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.regex.RegexSetRepository
import me.rerere.fawntavern.data.settings.CharacterModelStore
import me.rerere.fawntavern.data.worldbook.WorldBookRepository
import org.json.JSONObject

internal interface CharacterEditorDataSource {
    fun imageFile(name: String): File
    suspend fun saveImage(name: String, uri: Uri): Boolean
    suspend fun deleteImage(name: String)
    suspend fun updateJson(name: String, transform: (JSONObject) -> Unit)
    fun apiConfig(): ApiConfig
    fun model(key: String): String
    fun saveModel(key: String, model: String)
    suspend fun presetOptions(): List<CharacterAssociationOption>
    suspend fun worldBookOptions(): List<CharacterAssociationOption>
    suspend fun regexOptions(): List<CharacterAssociationOption>
}

internal data class CharacterAssociationOption(val id: String, val label: String)

internal class AndroidCharacterEditorDataSource(
    private val context: Context,
) : CharacterEditorDataSource {
    override fun imageFile(name: String): File = CharacterRepository.imageFile(context, name)
    override suspend fun saveImage(name: String, uri: Uri): Boolean =
        CharacterRepository.saveImageFromUri(context, name, uri)
    override suspend fun deleteImage(name: String) = CharacterRepository.deleteImage(context, name)
    override suspend fun updateJson(name: String, transform: (JSONObject) -> Unit) {
        CharacterRepository.updateJson(context, name, transform)
    }
    override fun apiConfig(): ApiConfig = ApiConfigStore.loadConfig(context)
    override fun model(key: String): String = CharacterModelStore.get(context, key)
    override fun saveModel(key: String, model: String) = CharacterModelStore.set(context, key, model)
    override suspend fun presetOptions(): List<CharacterAssociationOption> =
        PresetRepository.listNames(context).mapNotNull { name ->
            runCatching { PresetRepository.load(context, name) }
                .getOrNull()?.let { CharacterAssociationOption(it.id, it.name) }
        }
    override suspend fun worldBookOptions(): List<CharacterAssociationOption> =
        WorldBookRepository.listNames(context).mapNotNull { name ->
            runCatching { WorldBookRepository.load(context, name) }
                .getOrNull()?.let { CharacterAssociationOption(it.id, it.name) }
        }
    override suspend fun regexOptions(): List<CharacterAssociationOption> =
        RegexSetRepository.loadAll(context).filterNot { it.global }
            .map { CharacterAssociationOption(it.id, it.name) }
}

internal class CharacterEditorController(
    private val dataSource: CharacterEditorDataSource,
) {
    fun imageFile(name: String): File = dataSource.imageFile(name)
    suspend fun saveImage(name: String, uri: Uri): Boolean = dataSource.saveImage(name, uri)
    suspend fun deleteImage(name: String) = dataSource.deleteImage(name)
    suspend fun updateJson(name: String, transform: (JSONObject) -> Unit) = dataSource.updateJson(name, transform)
    fun apiConfig(): ApiConfig = dataSource.apiConfig()
    fun model(key: String): String = dataSource.model(key)
    fun saveModel(key: String, model: String) = dataSource.saveModel(key, model)
    suspend fun presetOptions(): List<CharacterAssociationOption> = dataSource.presetOptions()
    suspend fun worldBookOptions(): List<CharacterAssociationOption> = dataSource.worldBookOptions()
    suspend fun regexOptions(): List<CharacterAssociationOption> = dataSource.regexOptions()
}
