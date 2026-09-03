package me.rerere.fawntavern.plugin.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.fawntavern.plugin.ipc.IPluginWorker
import me.rerere.fawntavern.plugin.ipc.IPluginWorkerCallback
import org.json.JSONObject

internal const val WORKER_TIMEOUT_ERROR = "fawntavern-worker-timeout"

/** QuickJS process boundary. A watchdog kills this process when native evaluation cannot return. */
class PluginWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "plugin-watchdog").apply { isDaemon = true }
    }
    private val runtimes = ConcurrentHashMap<String, PluginWorkerRuntime>()
    private val hostCalls = ConcurrentHashMap<String, CompletableDeferred<HostResponse>>()
    @Volatile private var callback: IPluginWorkerCallback? = null

    private val binder = object : IPluginWorker.Stub() {
        override fun setCallback(value: IPluginWorkerCallback?) {
            callback = value
        }

        override fun loadPlugin(
            requestId: String?,
            pluginId: String?,
            source: String?,
            entryName: String?,
            timeoutMs: Long,
        ) {
            val request = requestId.orEmpty()
            val id = pluginId.orEmpty()
            guarded(request, id, timeoutMs) {
                runtimes.remove(id)?.close()
                val runtime = PluginWorkerRuntime(
                    pluginId = id,
                    source = source.orEmpty(),
                    entryName = entryName.orEmpty().ifBlank { "plugin.js" },
                    hostCall = ::requestHostCall,
                )
                try {
                    runtime.initialize()
                    runtimes[id] = runtime
                    JSONObject().put("loaded", true).toString()
                } catch (error: Throwable) {
                    runCatching { runtime.close() }
                    throw error
                }
            }
        }

        override fun invoke(
            requestId: String?,
            pluginId: String?,
            capability: String?,
            method: String?,
            argumentJson: String?,
            configJson: String?,
            timeoutMs: Long,
        ) {
            val request = requestId.orEmpty()
            val id = pluginId.orEmpty()
            guarded(request, id, timeoutMs) {
                val runtime = runtimes[id] ?: error("插件尚未加载")
                runtime.invoke(
                    capability = capability.orEmpty(),
                    method = method.orEmpty(),
                    argumentJson = argumentJson ?: "{}",
                    configJson = configJson ?: "{}",
                )
            }
        }

        override fun unloadPlugin(pluginId: String?) {
            val runtime = runtimes.remove(pluginId.orEmpty()) ?: return
            scope.launch { runtime.close() }
        }

        override fun resolveHostCall(requestId: String?, ok: Boolean, payloadJson: String?) {
            hostCalls.remove(requestId.orEmpty())?.complete(HostResponse(ok, payloadJson ?: "null"))
        }

    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        watchdog.shutdownNow()
        super.onDestroy()
    }

    private fun guarded(
        requestId: String,
        pluginId: String,
        timeoutMs: Long,
        block: suspend () -> String,
    ) {
        val deadline = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val killTask = watchdog.schedule(
            {
                sendResult(requestId, pluginId, false, WORKER_TIMEOUT_ERROR)
                Process.killProcess(Process.myPid())
            },
            deadline,
            TimeUnit.MILLISECONDS,
        )
        scope.launch {
            val result = runCatching { block() }
            killTask.cancel(false)
            result.fold(
                onSuccess = { sendResult(requestId, pluginId, true, it) },
                onFailure = { sendResult(requestId, pluginId, false, it.message ?: it::class.java.simpleName) },
            )
        }
    }

    private suspend fun requestHostCall(
        pluginId: String,
        sessionId: String,
        method: String,
        paramsJson: String,
    ): String {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<HostResponse>()
        hostCalls[requestId] = deferred
        val delivered = runCatching {
            callback?.onHostCall(requestId, pluginId, sessionId, method, paramsJson)
                ?: error("宿主连接已断开")
        }.isSuccess
        if (!delivered) {
            hostCalls.remove(requestId)
            error("无法调用宿主服务")
        }
        val response = deferred.await()
        if (!response.ok) error(response.payload)
        return response.payload
    }

    private fun sendResult(requestId: String, pluginId: String, ok: Boolean, payload: String) {
        runCatching { callback?.onResult(requestId, pluginId, ok, payload) }
    }

    private data class HostResponse(val ok: Boolean, val payload: String)

    private companion object {
        const val MIN_TIMEOUT_MS = 250L
        const val MAX_TIMEOUT_MS = 30_000L
    }
}
