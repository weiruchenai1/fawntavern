package me.rerere.fawntavern.ui.settings

import android.content.Context
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiConfigStore
import me.rerere.fawntavern.data.settings.DefaultModelRole
import me.rerere.fawntavern.data.settings.DefaultModelStore

internal class AndroidDefaultModelDataSource(
    private val context: Context,
) : DefaultModelDataSource {
    override fun apiConfig(): ApiConfig = ApiConfigStore.loadConfig(context)
    override fun entry(role: DefaultModelRole): DefaultModelEntry =
        DefaultModelStore.get(context, role.storageKey).let { DefaultModelEntry(it.model, it.prompt) }
    override fun setModel(role: DefaultModelRole, model: String) =
        DefaultModelStore.setModel(context, role.storageKey, model)
    override fun setPrompt(role: DefaultModelRole, prompt: String) =
        DefaultModelStore.setPrompt(context, role.storageKey, prompt)
    override fun reset(role: DefaultModelRole) = DefaultModelStore.reset(context, role.storageKey)
}
