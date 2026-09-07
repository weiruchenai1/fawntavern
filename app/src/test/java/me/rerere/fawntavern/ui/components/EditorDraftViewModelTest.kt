package me.rerere.fawntavern.ui.components

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.fawntavern.core.resource.EditorDraftStorage
import me.rerere.fawntavern.data.preset.PromptItem
import me.rerere.fawntavern.data.preset.StPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorDraftViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun aFreshViewModelRestoresALargeDraftFromStorage() = runTest(dispatcher) {
        val storage = MemoryDraftStorage()
        val original = StPreset(name = "preset")
        val edited = original.copy(prompts = listOf(PromptItem("prompt", content = "长文本".repeat(400_000))))
        val first = EditorDraftViewModel(storage, StPreset.serializer(), dispatcher)
        first.open("draft", original)
        first.update(edited)
        advanceUntilIdle()
        val recreated = EditorDraftViewModel(storage, StPreset.serializer(), dispatcher)
        recreated.open("draft", original)
        assertEquals(edited, recreated.value)
    }

    @Test
    fun failedSaveRetainsTheDraftAndRetryClearsItOnlyAfterCommit() = runTest(dispatcher) {
        val storage = MemoryDraftStorage()
        val model = EditorDraftViewModel(storage, String.serializer(), dispatcher)
        model.open("draft", "original")
        model.update("edited")
        model.save { error("write failed") }
        advanceUntilIdle()
        assertEquals("write failed", model.errors.first().message)
        assertEquals("edited", model.value)
        assertEquals("edited", Json.decodeFromString<String>(storage.read("draft")!!))
        assertNull(model.completedKey)
        assertFalse(model.saving)
        var committed = ""
        model.save { committed = it }
        advanceUntilIdle()
        assertEquals("edited", committed)
        assertEquals("draft", model.completedKey)
        assertNull(storage.read("draft"))
    }

    @Test
    fun editsArrivingDuringSaveAreCommittedBeforeLeaving() = runTest(dispatcher) {
        val storage = MemoryDraftStorage()
        val release = CompletableDeferred<Unit>()
        val commits = mutableListOf<String>()
        val model = EditorDraftViewModel(storage, String.serializer(), dispatcher)
        model.open("draft", "original")
        model.update("first")
        model.save { commits += it; release.await() }
        runCurrent()
        assertTrue(model.saving)
        model.update("last")
        model.save { error("second save must be ignored") }
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("first", "last"), commits)
        assertNull(storage.read("draft"))
        assertEquals("draft", model.completedKey)
    }

    @Test
    fun openingAnotherResourceDoesNotRestoreThePreviousDraft() = runTest(dispatcher) {
        val storage = MemoryDraftStorage()
        val model = EditorDraftViewModel(storage, String.serializer(), dispatcher)
        model.open("first", "first initial")
        model.update("first edited")
        advanceUntilIdle()
        model.open("second", "second initial")
        assertEquals("second initial", model.value)
        model.open("first", "outdated disk value")
        assertEquals("first edited", model.value)
    }

    @Test
    fun editsArrivingDuringCheckpointRemovalAreSavedBeforeCompletion() = runTest(dispatcher) {
        val removing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val storage = object : MemoryDraftStorage() {
            override suspend fun remove(key: String) {
                removing.complete(Unit)
                release.await()
                super.remove(key)
            }
        }
        val commits = mutableListOf<String>()
        val model = EditorDraftViewModel(storage, String.serializer(), dispatcher)
        model.open("draft", "original")
        model.update("first")
        model.save { commits += it }
        removing.await()

        model.update("last")
        assertNull(model.completedKey)
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first", "last"), commits)
        assertEquals("last", model.value)
        assertEquals("draft", model.completedKey)
        assertNull(storage.read("draft"))
    }

    private open class MemoryDraftStorage : EditorDraftStorage {
        private val entries = mutableMapOf<String, String>()
        override suspend fun read(key: String) = entries[key]
        override suspend fun write(key: String, value: String) { entries[key] = value }
        override suspend fun remove(key: String) { entries.remove(key) }
    }
}
