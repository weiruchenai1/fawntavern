package me.rerere.fawntavern

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.fawntavern.data.backup.AppBackup
import me.rerere.fawntavern.core.diagnostics.CrashReportStore
import me.rerere.fawntavern.core.diagnostics.SafeLog
import me.rerere.fawntavern.data.diagnostics.RemoteDiagnostics
import me.rerere.fawntavern.data.settings.AppStatisticsStore
import me.rerere.fawntavern.plugin.PluginManager

class FawnTavernApplication : Application() {
    sealed interface RecoveryState {
        data object Recovering : RecoveryState
        data object Ready : RecoveryState
        data class Failed(val error: Throwable) : RecoveryState
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _recoveryState = MutableStateFlow<RecoveryState>(RecoveryState.Recovering)
    val recoveryState: StateFlow<RecoveryState> = _recoveryState.asStateFlow()
    private var recoveryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // 隔离插件进程只运行 PluginWorkerService；不得初始化数据库、备份、统计或网络诊断。
        if (currentProcessName().endsWith(PLUGIN_PROCESS_SUFFIX)) return
        AppStatisticsStore.incrementLaunchCount(this)
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "unknown" }
        SafeLog.initialize(this)
        CrashReportStore.install(this, versionName)
        RemoteDiagnostics.applySavedPreference(this)
        retryRecovery()
    }

    fun retryRecovery() {
        if (recoveryJob?.isActive == true) return
        _recoveryState.value = RecoveryState.Recovering
        recoveryJob = applicationScope.launch {
            _recoveryState.value = try {
                AppBackup.recoverInterruptedImport(this@FawnTavernApplication)
                PluginManager.initialize(this@FawnTavernApplication)
                RecoveryState.Ready
            } catch (error: Throwable) {
                RecoveryState.Failed(error)
            }
        }
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName
            ?: runCatching {
                File("/proc/self/cmdline").readText().trimEnd('\u0000')
            }.getOrDefault("")
    }

    private companion object {
        const val PLUGIN_PROCESS_SUFFIX = ":plugin_worker"
    }
}
