package me.rerere.fawntavern.ui.character

import android.net.Uri
import java.io.File
import kotlinx.coroutines.runBlocking
import me.rerere.fawntavern.data.character.CharacterCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterLibraryControllerTest {
    @Test
    fun loadKeepsNameOrderAndReportsBrokenCards() = runBlocking {
        val source = FakeCharacterLibraryDataSource()
        val errors = mutableListOf<String>()
        val controller = CharacterLibraryController(source) { name, _ -> errors += name }

        val state = controller.load()

        assertEquals(listOf("valid", "broken"), state.names)
        assertEquals(CharacterCard(name = "Valid"), state.cards["valid"])
        assertFalse("broken" in state.cards)
        assertEquals(listOf("broken"), errors)
        assertTrue(controller.imageFile("valid").path.endsWith("valid"))
    }

    private class FakeCharacterLibraryDataSource : CharacterLibraryDataSource {
        override fun defaultCardName(): String? = "valid"
        override suspend fun names(): List<String> = listOf("valid", "broken")
        override suspend fun load(name: String): CharacterCard {
            if (name == "broken") error("broken card")
            return CharacterCard(name = "Valid")
        }
        override suspend fun create(name: String): CharacterCard = CharacterCard(name = name)
        override suspend fun import(uri: Uri): CharacterCard = CharacterCard(name = "Imported")
        override suspend fun delete(name: String) = Unit
        override suspend fun saveOrder(names: List<String>) = Unit
        override suspend fun exportPng(name: String): ByteArray = byteArrayOf()
        override suspend fun exportJson(name: String): ByteArray = byteArrayOf()
        override fun imageFile(name: String): File = File("cards", name)
    }
}
