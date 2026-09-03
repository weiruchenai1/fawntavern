package me.rerere.fawntavern.ui.settings

import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.settings.DefaultModelPrompts
import me.rerere.fawntavern.data.settings.DefaultModelRole

data class DefaultModelEntry(
    val model: String = "",
    val prompt: String = "",
)

data class DefaultModelState(
    val apiConfig: ApiConfig,
    val entries: Map<DefaultModelRole, DefaultModelEntry>,
) {
    fun entry(role: DefaultModelRole): DefaultModelEntry = entries[role] ?: DefaultModelEntry()
}

interface DefaultModelDataSource {
    fun apiConfig(): ApiConfig
    fun entry(role: DefaultModelRole): DefaultModelEntry
    fun setModel(role: DefaultModelRole, model: String)
    fun setPrompt(role: DefaultModelRole, prompt: String)
    fun reset(role: DefaultModelRole)
}

class DefaultModelController(
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
        DefaultModelRole.TITLE -> DefaultModelPrompts.TITLE
        DefaultModelRole.SUMMARY -> DefaultModelPrompts.SUMMARY
        DefaultModelRole.TRANSLATION -> DefaultModelPrompts.TRANSLATION
        DefaultModelRole.CHAT -> ""
    }

    private fun DefaultModelState.withEntry(role: DefaultModelRole, entry: DefaultModelEntry): DefaultModelState =
        copy(entries = entries + (role to entry))
}
