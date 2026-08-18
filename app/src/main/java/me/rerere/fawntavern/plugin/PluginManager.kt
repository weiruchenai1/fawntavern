package me.rerere.fawntavern.plugin

import android.content.Context
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.fawntavern.BuildConfig
import me.rerere.fawntavern.extension.BuiltinExtensions
import me.rerere.fawntavern.extension.ExtensionHost
import me.rerere.fawntavern.extension.ExtensionStore
import me.rerere.fawntavern.plugin.runtime.PluginWorkerClient

/** Single owner for installed plugin state and runtime lifecycle. */
object PluginManager {
    enum class RuntimeState { DISABLED, ACTIVE, FAULTED, INCOMPATIBLE }

    data class PluginRecord(
        val plugin: PluginRepository.InstalledPlugin,
        val state: RuntimeState,
        val failures: Int = 0,
        val lastError: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initLock = Mutex()
    private val installed = ConcurrentHashMap<String, PluginRepository.InstalledPlugin>()
    private val failureCounts = ConcurrentHashMap<String, Int>()
    private val _plugins = MutableStateFlow<List<PluginRecord>>(emptyList())
    val plugins: StateFlow<List<PluginRecord>> = _plugins.asStateFlow()
    @Volatile private var appContext: Context? = null
    @Volatile private var initialized = false

    suspend fun initialize(context: Context) = initLock.withLock {
        if (initialized) return
        val app = context.applicationContext
        appContext = app
        PluginInstaller.recoverInterruptedInstalls(app)
        BuiltinExtensions.registerAll()
        PluginWorkerClient.initialize(app)
        PluginRepository.list(app).forEach { register(app, it) }
        initialized = true
    }

    suspend fun installFromZip(input: InputStream): PluginRepository.InstalledPlugin {
        val context = requireContext()
        val plugin = PluginInstaller.installFromZip(context, input)
        register(context, plugin)
        return plugin
    }

    suspend fun installFromGitHub(url: String): PluginRepository.InstalledPlugin {
        val context = requireContext()
        val plugin = PluginInstaller.installFromGitHub(context, url)
        register(context, plugin)
        return plugin
    }

    suspend fun setEnabled(pluginId: String, enabled: Boolean) {
        val context = requireContext()
        val plugin = installed[pluginId] ?: return
        if (enabled && !plugin.manifest.isCompatibleWith(BuildConfig.VERSION_NAME)) {
            ExtensionStore.setEnabled(context, pluginId, false)
            upsert(plugin, RuntimeState.INCOMPATIBLE)
            return
        }
        ExtensionStore.setEnabled(context, pluginId, enabled)
        failureCounts.remove(pluginId)
        if (!enabled) PluginWorkerClient.unload(pluginId)
        upsert(plugin, if (enabled) RuntimeState.ACTIVE else RuntimeState.DISABLED)
    }

    fun requestSetEnabled(pluginId: String, enabled: Boolean) {
        scope.launch { setEnabled(pluginId, enabled) }
    }

    suspend fun uninstall(pluginId: String): Boolean {
        val context = requireContext()
        PluginWorkerClient.unload(pluginId)
        val deleted = PluginInstaller.uninstall(context, pluginId)
        if (!deleted) return false
        ExtensionHost.unregister(pluginId)
        ExtensionStore.setEnabled(context, pluginId, false)
        failureCounts.remove(pluginId)
        installed.remove(pluginId)
        _plugins.update { records -> records.filterNot { it.plugin.manifest.id == pluginId } }
        return true
    }

    fun recordSuccess(pluginId: String) {
        failureCounts.remove(pluginId)
        _plugins.update { records ->
            records.map { record ->
                if (record.plugin.manifest.id == pluginId && record.state != RuntimeState.DISABLED) {
                    record.copy(state = RuntimeState.ACTIVE, failures = 0, lastError = "")
                } else record
            }
        }
    }

    fun recordFailure(pluginId: String, message: String, fatal: Boolean) {
        val count = failureCounts.compute(pluginId) { _, previous -> (previous ?: 0) + 1 } ?: 1
        val disable = fatal || count >= MAX_CONSECUTIVE_FAILURES
        _plugins.update { records ->
            records.map { record ->
                if (record.plugin.manifest.id == pluginId) {
                    record.copy(
                        state = if (disable) RuntimeState.FAULTED else record.state,
                        failures = count,
                        lastError = message.take(MAX_ERROR_CHARS),
                    )
                } else record
            }
        }
        if (disable) {
            appContext?.let { ExtensionStore.setEnabled(it, pluginId, false) }
            scope.launch { PluginWorkerClient.unload(pluginId) }
        }
    }

    private fun register(context: Context, plugin: PluginRepository.InstalledPlugin) {
        installed[plugin.manifest.id] = plugin
        ExtensionHost.replace(PluginExtension(plugin))
        if (!plugin.manifest.isCompatibleWith(BuildConfig.VERSION_NAME)) {
            ExtensionStore.setEnabled(context, plugin.manifest.id, false)
            upsert(plugin, RuntimeState.INCOMPATIBLE)
            return
        }
        val enabled = ExtensionStore.isEnabled(context, plugin.manifest.id, default = false)
        upsert(plugin, if (enabled) RuntimeState.ACTIVE else RuntimeState.DISABLED)
    }

    private fun upsert(plugin: PluginRepository.InstalledPlugin, state: RuntimeState) {
        _plugins.update { records ->
            val next = PluginRecord(plugin = plugin, state = state)
            val index = records.indexOfFirst { it.plugin.manifest.id == plugin.manifest.id }
            if (index < 0) (records + next).sortedBy { it.plugin.manifest.name.lowercase() }
            else records.toMutableList().apply { set(index, next) }
        }
    }

    private fun requireContext(): Context = checkNotNull(appContext) { "PluginManager 尚未初始化" }

    private const val MAX_CONSECUTIVE_FAILURES = 3
    private const val MAX_ERROR_CHARS = 300
}
