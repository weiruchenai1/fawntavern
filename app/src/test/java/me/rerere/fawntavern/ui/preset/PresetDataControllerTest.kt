package me.rerere.fawntavern.ui.preset

import android.net.Uri
import kotlinx.coroutines.runBlocking
import me.rerere.fawntavern.data.preset.RegexScript
import me.rerere.fawntavern.data.preset.StPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class PresetDataControllerTest {
    @Test
    fun forwardsListLoadSaveRenameAndDelete() = runBlocking {
        val source = FakePresetDataSource()
        val controller = PresetDataController(source)

        assertEquals(true, controller.isDefault("preset"))
        assertEquals(listOf("preset"), controller.names())
        assertEquals(StPreset(name = "preset"), controller.load("preset"))
        controller.save(StPreset(name = "saved"))
        controller.rename("preset", "renamed")
        controller.delete("renamed")

        assertEquals("saved", source.saved?.name)
        assertEquals("preset" to "renamed", source.renamed)
        assertEquals("renamed", source.deleted)
    }

    private class FakePresetDataSource : PresetDataSource {
        var saved: StPreset? = null
        var renamed: Pair<String, String>? = null
        var deleted: String? = null

        override fun defaultName(): String = "preset"
        override suspend fun names(): List<String> = listOf("preset")
        override suspend fun load(name: String): StPreset = StPreset(name = name)
        override suspend fun import(uri: Uri): StPreset = StPreset(name = "imported")
        override suspend fun rename(old: String, new: String): Boolean {
            renamed = old to new
            return true
        }
        override suspend fun delete(name: String) { deleted = name }
        override suspend fun save(preset: StPreset) { saved = preset }
        override suspend fun parseRegex(uri: Uri): RegexScript = RegexScript(scriptName = "regex")
    }
}
