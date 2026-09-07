package me.rerere.fawntavern.ui.character

import android.net.Uri
import java.io.File
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.character.CharacterCard
import org.json.JSONArray
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
    suspend fun save(name: String, card: CharacterCard) = dataSource.updateJson(name) { json ->
        json.put("name", card.name.trim())
        json.put("description", card.description)
        json.put("personality", card.personality)
        json.put("scenario", card.scenario)
        json.put("system_prompt", card.systemPrompt)
        json.put("post_history_instructions", card.postHistoryInstructions)
        json.put("mes_example", card.mesExample)
        json.put("creator_notes", card.creatorNotes)
        json.put("tags", JSONArray(card.tags))
        json.put("first_mes", card.firstMes)
        json.put("alternate_greetings", JSONArray(card.alternateGreetings))
        json.put("enabled_world_book_ids", JSONArray(card.enabledWorldBookIds))
        json.put("linked_preset_id", card.linkedPresetId)
        json.put("enabled_regex_ids", JSONArray(card.enabledRegexIds))
        json.put("streaming", card.streaming)
        val extensions = json.optJSONObject("extensions") ?: JSONObject().also { json.put("extensions", it) }
        val depthPrompt = card.depthPrompt
        if (depthPrompt == null || depthPrompt.prompt.isBlank()) {
            extensions.remove("depth_prompt")
        } else {
            extensions.put("depth_prompt", JSONObject()
                .put("prompt", depthPrompt.prompt)
                .put("depth", depthPrompt.depth)
                .put("role", depthPrompt.role))
        }
    }
    fun apiConfig(): ApiConfig = dataSource.apiConfig()
    fun model(key: String): String = dataSource.model(key)
    fun saveModel(key: String, model: String) = dataSource.saveModel(key, model)
    suspend fun presetOptions(): List<CharacterAssociationOption> = dataSource.presetOptions()
    suspend fun worldBookOptions(): List<CharacterAssociationOption> = dataSource.worldBookOptions()
    suspend fun regexOptions(): List<CharacterAssociationOption> = dataSource.regexOptions()
}
