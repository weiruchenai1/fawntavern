package me.rerere.fawntavern.ui.extension

interface ExtensionSettingsDataSource {
    fun isEnabled(id: String): Boolean
    fun setEnabled(id: String, enabled: Boolean)
    fun config(id: String): String
    fun setConfig(id: String, config: String)
}

class ExtensionSettingsController(
    private val dataSource: ExtensionSettingsDataSource,
) {
    fun isEnabled(id: String): Boolean = dataSource.isEnabled(id)
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        dataSource.setEnabled(id, enabled)
        return enabled
    }
    fun config(id: String): String = dataSource.config(id)
    fun setConfig(id: String, config: String) = dataSource.setConfig(id, config)
}
