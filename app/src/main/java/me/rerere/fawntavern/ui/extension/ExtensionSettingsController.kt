package me.rerere.fawntavern.ui.extension

import android.content.Context
import me.rerere.fawntavern.extension.ExtensionStore
import me.rerere.fawntavern.extension.ExtensionHost
import me.rerere.fawntavern.plugin.PluginManager

internal interface ExtensionSettingsDataSource {
    fun isEnabled(id: String): Boolean
    fun setEnabled(id: String, enabled: Boolean)
    fun config(id: String): String
    fun setConfig(id: String, config: String)
}

internal class AndroidExtensionSettingsDataSource(
    private val context: Context,
) : ExtensionSettingsDataSource {
    override fun isEnabled(id: String): Boolean = ExtensionStore.isEnabled(context, id)
    override fun setEnabled(id: String, enabled: Boolean) {
        if (ExtensionHost.byId(id)?.info?.builtin == false) {
            PluginManager.requestSetEnabled(id, enabled)
        } else {
            ExtensionStore.setEnabled(context, id, enabled)
        }
    }
    override fun config(id: String): String = ExtensionStore.getConfig(context, id)
    override fun setConfig(id: String, config: String) = ExtensionStore.setConfig(context, id, config)
}

internal class ExtensionSettingsController(
    private val dataSource: ExtensionSettingsDataSource,
) {
    constructor(context: Context) : this(AndroidExtensionSettingsDataSource(context))

    fun isEnabled(id: String): Boolean = dataSource.isEnabled(id)
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        dataSource.setEnabled(id, enabled)
        return enabled
    }
    fun config(id: String): String = dataSource.config(id)
    fun setConfig(id: String, config: String) = dataSource.setConfig(id, config)
}
