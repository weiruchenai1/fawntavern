package me.rerere.fawntavern.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import me.rerere.fawntavern.core.resource.EditorDraftStorage

/** 页面重建复用内存草稿，进程重建从文件恢复；保存成功后才清理检查点。 */
internal class EditorDraftViewModel<T>(
    private val storage: EditorDraftStorage,
    private val serializer: KSerializer<T>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val writeMutex = Mutex()
    private val errorChannel = Channel<Exception>(Channel.BUFFERED)
    val errors = errorChannel.receiveAsFlow()
    private var revision = 0L

    var key by mutableStateOf<String?>(null)
        private set
    var value by mutableStateOf<T?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var completedKey by mutableStateOf<String?>(null)
        private set

    suspend fun open(key: String, initial: T) {
        if (this.key == key && value != null) return
        this.key = key
        val request = ++revision
        value = null
        completedKey = null
        try {
            val restored = withContext(ioDispatcher) {
                storage.read(key)?.let { json.decodeFromString(serializer, it) }
            }
            if (this.key == key && revision == request) value = restored ?: initial
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (this.key == key && revision == request) {
                value = initial
                errorChannel.trySend(error)
            }
        }
    }

    fun update(value: T) {
        if (this.value == value || completedKey != null) return
        this.value = value
        val request = ++revision
        val key = key ?: return
        viewModelScope.launch {
            try {
                writeMutex.withLock {
                    if (this@EditorDraftViewModel.key == key && revision == request && completedKey == null) {
                        write(key, value)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorChannel.trySend(error)
            }
        }
    }

    fun save(commit: suspend (T) -> Unit) {
        if (saving || value == null || completedKey != null) return
        val key = key ?: return
        saving = true
        viewModelScope.launch {
            try {
                writeMutex.withLock {
                    while (this@EditorDraftViewModel.key == key) {
                        val snapshot = value ?: return@withLock
                        val request = revision
                        write(key, snapshot)
                        commit(snapshot)
                        if (request != revision) continue
                        storage.remove(key)
                        // 清理文件也会挂起，期间的新输入仍须提交后才能退出。
                        if (request != revision) continue
                        completedKey = key
                        break
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorChannel.trySend(error)
            } finally {
                saving = false
            }
        }
    }

    private suspend fun write(key: String, snapshot: T) = withContext(ioDispatcher) {
        storage.write(key, json.encodeToString(serializer, snapshot))
    }
}
