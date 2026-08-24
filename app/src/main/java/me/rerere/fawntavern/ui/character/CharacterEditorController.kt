package me.rerere.fawntavern.ui.character

import android.content.Context
import android.net.Uri
import java.io.File
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiConfigStore
import me.rerere.fawntavern.data.character.CharacterRepository
import me.rerere.fawntavern.data.preset.PresetRepository
import me.rerere.fawntavern.data.settings.CharacterModelStore
import me.rerere.fawntavern.data.worldbook.WorldBookRepository
import org.json.JSONObject

internal data class CharacterRegexOption(
    val id: String,
    val displayName: String,
)

internal interface CharacterEditorDataSource {
    fun imageFile(name: String): File
    suspend fun saveImage(name: String, uri: Uri): Boolean
    suspend fun deleteImage(name: String)
    suspend fun updateJson(name: String, transform: (JSONObject) -> Unit)
    fun apiConfig(): ApiConfig
    fun model(key: String): String
    fun saveModel(key: String, model: String)
    suspend fun presetNames(): List<String>
    suspend fun worldBookNames(): List<String>
    suspend fun regexOptions(): List<CharacterRegexOption>
}

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
    override suspend fun presetNames(): List<String> = PresetRepository.listNames(context)
    override suspend fun worldBookNames(): List<String> = WorldBookRepository.listNames(context)
    override suspend fun regexOptions(): List<CharacterRegexOption> =
        CharacterRepository.listNames(context).mapNotNull { fileName ->
            runCatching {
                val card = CharacterRepository.load(context, fileName)
                CharacterRegexOption(fileName, card.name.ifBlank { fileName })
            }.getOrNull()
        }
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
    suspend fun presetNames(): List<String> = dataSource.presetNames()
    suspend fun worldBookNames(): List<String> = dataSource.worldBookNames()
    suspend fun regexOptions(): List<CharacterRegexOption> = dataSource.regexOptions()
}
