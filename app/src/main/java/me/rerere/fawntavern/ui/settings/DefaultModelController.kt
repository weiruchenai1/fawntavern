package me.rerere.fawntavern.ui.settings

import android.content.Context
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiConfigStore
import me.rerere.fawntavern.data.settings.DefaultModelStore

internal enum class DefaultModelRole(val storageKey: String) {
    CHAT(DefaultModelStore.ROLE_CHAT),
    TITLE(DefaultModelStore.ROLE_TITLE),
    SUMMARY(DefaultModelStore.ROLE_SUMMARY),
    TRANSLATION(DefaultModelStore.ROLE_TRANSLATION),
}

internal data class DefaultModelEntry(
    val model: String = "",
    val prompt: String = "",
)

internal data class DefaultModelState(
    val apiConfig: ApiConfig,
    val entries: Map<DefaultModelRole, DefaultModelEntry>,
) {
    fun entry(role: DefaultModelRole): DefaultModelEntry = entries[role] ?: DefaultModelEntry()
}

internal interface DefaultModelDataSource {
    fun apiConfig(): ApiConfig
    fun entry(role: DefaultModelRole): DefaultModelEntry
    fun setModel(role: DefaultModelRole, model: String)
    fun setPrompt(role: DefaultModelRole, prompt: String)
    fun reset(role: DefaultModelRole)
}

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

internal class DefaultModelController(
    private val dataSource: DefaultModelDataSource,
) {
    fun load(): DefaultModelState = DefaultModelState(
        apiConfig = dataSource.apiConfig(),
        entries = DefaultModelRole.entries.associateWith(dataSource::entry),
    )

    fun setModel(state: DefaultModelState, role: DefaultModelRole, model: String): DefaultModelState {
        dataSource.setModel(role, model)
        return state.withEntry(role, state.entry(role).copy(model = model))
    }

    fun setPrompt(state: DefaultModelState, role: DefaultModelRole, prompt: String): DefaultModelState {
        dataSource.setPrompt(role, prompt)
        return state.withEntry(role, state.entry(role).copy(prompt = prompt))
    }

    fun reset(state: DefaultModelState, role: DefaultModelRole): DefaultModelState {
        dataSource.reset(role)
        return state.withEntry(role, DefaultModelEntry())
    }

    fun defaultPrompt(role: DefaultModelRole): String = when (role) {
        DefaultModelRole.TITLE -> DefaultModelStore.DEFAULT_TITLE_PROMPT
        DefaultModelRole.SUMMARY -> DefaultModelStore.DEFAULT_SUMMARY_PROMPT
        DefaultModelRole.TRANSLATION -> DefaultModelStore.DEFAULT_TRANSLATION_PROMPT
        DefaultModelRole.CHAT -> ""
    }

    private fun DefaultModelState.withEntry(role: DefaultModelRole, entry: DefaultModelEntry): DefaultModelState =
        copy(entries = entries + (role to entry))
}
