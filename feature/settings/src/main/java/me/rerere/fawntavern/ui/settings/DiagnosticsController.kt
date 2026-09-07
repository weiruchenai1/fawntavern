package me.rerere.fawntavern.ui.settings

data class DiagnosticsState(
    val remoteAvailable: Boolean,
    val remoteEnabled: Boolean,
    val report: String?,
    val safeLogCount: Int,
)

interface DiagnosticsDataSource {
    fun load(): DiagnosticsState
    fun setRemoteEnabled(enabled: Boolean): Boolean
    fun clearReport(): Boolean
    fun safeLogsText(): String
}

class DiagnosticsController(
    private val dataSource: DiagnosticsDataSource,
) {
    fun load(): DiagnosticsState = dataSource.load()
    fun setRemoteEnabled(enabled: Boolean): Boolean = dataSource.setRemoteEnabled(enabled)
    fun clearReport(): Boolean = dataSource.clearReport()
    fun safeLogsText(): String = dataSource.safeLogsText()
}
