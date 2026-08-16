package me.rerere.fawntavern

import android.app.Application
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
                RecoveryState.Ready
            } catch (error: Throwable) {
                RecoveryState.Failed(error)
            }
        }
    }
}
