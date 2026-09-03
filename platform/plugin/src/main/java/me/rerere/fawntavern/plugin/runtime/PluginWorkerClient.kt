package me.rerere.fawntavern.plugin.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.fawntavern.core.diagnostics.SafeLog
import me.rerere.fawntavern.data.chat.ChatRepository
import me.rerere.fawntavern.plugin.PluginRepository
import me.rerere.fawntavern.plugin.ipc.IPluginWorker
import me.rerere.fawntavern.plugin.ipc.IPluginWorkerCallback
import org.json.JSONObject

/** Main-process client for the isolated QuickJS service. */
object PluginWorkerClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val pluginLocks = ConcurrentHashMap<String, Mutex>()
    private val stateLocks = ConcurrentHashMap<String, Mutex>()
    private val loaded = ConcurrentHashMap<String, String>()

    @Volatile private var appContext: Context? = null
    @Volatile private var worker: IPluginWorker? = null
    private var connectionWaiter: CompletableDeferred<IPluginWorker>? = null
    private var bound = false

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun invoke(
        plugin: PluginRepository.InstalledPlugin,
        capability: String,
        method: String,
        argumentJson: String,
        configJson: String,
        timeoutMs: Long,
    ): String {
        check(appContext != null) { "PluginWorkerClient 尚未初始化" }
        require(argumentJson.toByteArray(Charsets.UTF_8).size <= MAX_ARGUMENT_BYTES) {
            "插件调用参数超过 192KB"
        }
        return pluginLocks.getOrPut(plugin.manifest.id) { Mutex() }.withLock {
            val remote = connect()
            ensureLoaded(remote, plugin)
            request(timeoutMs + CLIENT_GRACE_MS) { requestId ->
                remote.invoke(
                    requestId,
                    plugin.manifest.id,
                    capability,
                    method,
                    argumentJson,
                    normalizeConfig(configJson),
                    timeoutMs,
                )
            }
        }
    }

    suspend fun unload(pluginId: String) {
        loaded.remove(pluginId)
        worker?.let { remote -> runCatching { remote.unloadPlugin(pluginId) } }
    }

    private suspend fun ensureLoaded(remote: IPluginWorker, plugin: PluginRepository.InstalledPlugin) {
        val fingerprint = "${plugin.manifest.version}:${plugin.meta.installedAt}"
        if (loaded[plugin.manifest.id] == fingerprint) return
        val source = withContext(Dispatchers.IO) { readEntry(plugin) }
        request(LOAD_TIMEOUT_MS + CLIENT_GRACE_MS) { requestId ->
            remote.loadPlugin(
                requestId,
                plugin.manifest.id,
                source,
                plugin.manifest.entry,
                LOAD_TIMEOUT_MS,
            )
        }
        loaded[plugin.manifest.id] = fingerprint
    }

    private suspend fun request(timeoutMs: Long, send: (String) -> Unit): String {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pending[requestId] = deferred
        return try {
            try {
                send(requestId)
            } catch (error: RemoteException) {
                val disconnected = PluginWorkerException(
                    message = "插件隔离进程连接已失效",
                )
                disconnect(disconnected)
                throw disconnected
            }
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(requestId)
        }
    }

    private suspend fun connect(): IPluginWorker {
        worker?.let { return it }
        val context = checkNotNull(appContext)
        val (waiter, startBinding) = synchronized(stateLock) {
            worker?.let { return it }
            connectionWaiter?.let { return@synchronized it to false }
            val created = CompletableDeferred<IPluginWorker>()
            connectionWaiter = created
            val shouldBind = !bound
            if (shouldBind) bound = true
            created to shouldBind
        }
        if (startBinding) {
            val started = runCatching {
                context.bindService(
                    Intent(context, PluginWorkerService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
            if (!started) {
                synchronized(stateLock) {
                    bound = false
                    connectionWaiter = null
                }
                waiter.completeExceptionally(PluginWorkerException("无法启动插件隔离进程"))
            }
        }
        return withTimeout(CONNECT_TIMEOUT_MS) { waiter.await() }
    }

    private val callback = object : IPluginWorkerCallback.Stub() {
        override fun onResult(requestId: String?, pluginId: String?, ok: Boolean, payloadJson: String?) {
            val deferred = pending.remove(requestId.orEmpty()) ?: return
            if (ok) deferred.complete(payloadJson ?: "null")
            else {
                val payload = payloadJson ?: "插件执行失败"
                deferred.completeExceptionally(
                    PluginWorkerException(
                        message = if (payload == WORKER_TIMEOUT_ERROR) "插件执行超时" else payload,
                        fatal = payload == WORKER_TIMEOUT_ERROR,
                    )
                )
            }
        }

        override fun onHostCall(
            requestId: String?,
            pluginId: String?,
            sessionId: String?,
            method: String?,
            paramsJson: String?,
        ) {
            val id = requestId.orEmpty()
            scope.launch {
                val response = runCatching {
                    handleHostCall(
                        pluginId = pluginId.orEmpty(),
                        sessionId = sessionId.orEmpty(),
                        method = method.orEmpty(),
                        paramsJson = paramsJson ?: "null",
                    )
                }
                val remote = worker ?: return@launch
                response.fold(
                    onSuccess = { remote.resolveHostCall(id, true, it) },
                    onFailure = { remote.resolveHostCall(id, false, it.message ?: "宿主调用失败") },
                )
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                disconnect(PluginWorkerException("插件隔离进程返回空 Binder"))
                appContext?.let { context -> runCatching { context.unbindService(this) } }
                synchronized(stateLock) { bound = false }
                return
            }
            val remote = IPluginWorker.Stub.asInterface(service)
            if (runCatching { remote.setCallback(callback) }.isFailure) {
                disconnect(PluginWorkerException("无法初始化插件隔离进程"))
                appContext?.let { context -> runCatching { context.unbindService(this) } }
                synchronized(stateLock) { bound = false }
                return
            }
            synchronized(stateLock) {
                worker = remote
                loaded.clear()
                connectionWaiter?.complete(remote)
                connectionWaiter = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            disconnect(PluginWorkerException("插件隔离进程已退出"))
        }

        override fun onBindingDied(name: ComponentName?) {
            disconnect(PluginWorkerException("插件隔离进程绑定失效"))
            appContext?.let { context -> runCatching { context.unbindService(this) } }
            synchronized(stateLock) { bound = false }
        }

        override fun onNullBinding(name: ComponentName?) {
            disconnect(PluginWorkerException("插件隔离进程未提供 Binder"))
            appContext?.let { context -> runCatching { context.unbindService(this) } }
            synchronized(stateLock) { bound = false }
        }
    }

    private fun disconnect(error: Throwable) {
        synchronized(stateLock) {
            worker = null
            loaded.clear()
            connectionWaiter?.completeExceptionally(error)
            connectionWaiter = null
        }
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private suspend fun handleHostCall(
        pluginId: String,
        sessionId: String,
        method: String,
        paramsJson: String,
    ): String = when (method) {
        "state.save" -> {
            require(sessionId.isNotBlank()) { "当前插件调用没有会话上下文" }
            val params = JSONObject(paramsJson)
            val value = params.opt("value")
            val state = if (value == null || value === JSONObject.NULL) "" else value.toString()
            require(state.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) { "插件状态超过 128KB" }
            stateLocks.getOrPut(sessionId) { Mutex() }.withLock {
                val context = checkNotNull(appContext)
                val latest = ChatRepository.get(context, sessionId) ?: error("会话不存在")
                val next = latest.extState.toMutableMap()
                if (state.isBlank()) next.remove(pluginId) else next[pluginId] = state
                ChatRepository.save(context, latest.copy(extState = next, updatedAt = System.currentTimeMillis()))
            }
            "null"
        }
        "log" -> {
            val level = runCatching { JSONObject(paramsJson).optString("level") }.getOrDefault("info")
            if (level == "error") SafeLog.warn(TAG, "plugin_reported_error")
            "null"
        }
        else -> error("宿主方法不可用: $method")
    }

    private fun readEntry(plugin: PluginRepository.InstalledPlugin): String {
        val root = plugin.dir.canonicalFile
        val entry = File(root, plugin.manifest.entry).canonicalFile
        require(entry.isFile && entry.path.startsWith(root.path + File.separator)) { "插件入口不存在或越界" }
        require(entry.length() <= MAX_SOURCE_BYTES) { "插件入口超过 384KB" }
        return entry.readText(Charsets.UTF_8)
    }

    private fun normalizeConfig(json: String): String {
        val normalized = runCatching { JSONObject(json).toString() }.getOrDefault("{}")
        require(normalized.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) { "插件配置超过 64KB" }
        return normalized
    }

    class PluginWorkerException(
        message: String,
        val fatal: Boolean = false,
    ) : Exception(message)

    private const val TAG = "PluginWorkerClient"
    private const val CONNECT_TIMEOUT_MS = 5_000L
    private const val LOAD_TIMEOUT_MS = 5_000L
    private const val CLIENT_GRACE_MS = 1_000L
    private const val MAX_SOURCE_BYTES = 384L * 1024
    private const val MAX_ARGUMENT_BYTES = 192 * 1024
    private const val MAX_STATE_BYTES = 128 * 1024
    private const val MAX_CONFIG_BYTES = 64 * 1024
}
