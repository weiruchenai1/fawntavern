package me.rerere.fawntavern.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.core.resource.EditorDraftStorage

class FileEditorDraftStorage(private val directory: File) : EditorDraftStorage {
    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        file(key).takeIf(File::exists)?.readText(Charsets.UTF_8)
    }

    override suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        JsonFileDir.atomicWriteText(file(key), value)
    }

    override suspend fun remove(key: String): Unit = withContext(Dispatchers.IO) {
        val file = file(key)
        check(!file.exists() || file.delete()) { "Unable to remove editor draft" }
    }

    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }
}
