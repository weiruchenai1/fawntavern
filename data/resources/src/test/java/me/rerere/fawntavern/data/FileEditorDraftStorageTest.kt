package me.rerere.fawntavern.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileEditorDraftStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun largeDraftSurvivesStorageRecreationAndCanBeRemoved() = runBlocking {
        val directory = temporary.newFolder("drafts")
        val content = "编辑内容".repeat(400_000)
        val first = FileEditorDraftStorage(directory)
        first.write("preset:one", content)
        val recreated = FileEditorDraftStorage(directory)
        assertEquals(content, recreated.read("preset:one"))
        recreated.write("preset:one", "latest")
        assertEquals("latest", first.read("preset:one"))
        recreated.remove("preset:one")
        assertNull(first.read("preset:one"))
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test
    fun resourceKeysRemainInsideTheDraftDirectory() = runBlocking {
        val directory = temporary.newFolder("drafts")
        val storage = FileEditorDraftStorage(directory)
        storage.write("../../outside", "draft")
        assertEquals("draft", storage.read("../../outside"))
        assertEquals(directory.canonicalFile, directory.listFiles()!!.single().canonicalFile.parentFile)
        assertFalse(File(temporary.root, "outside.json").exists())
    }
}
