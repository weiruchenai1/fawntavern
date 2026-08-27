package me.rerere.fawntavern.ui.character

import android.net.Uri
import java.io.File
import me.rerere.fawntavern.data.api.ApiConfig
import org.json.JSONObject

interface CharacterEditorDataSource {
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

data class CharacterAssociationOption(val id: String, val label: String)

class CharacterEditorController(
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
