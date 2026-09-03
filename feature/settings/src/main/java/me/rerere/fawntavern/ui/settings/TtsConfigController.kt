package me.rerere.fawntavern.ui.settings

import me.rerere.fawntavern.data.speech.TTSProviderSetting

data class TtsConfigState(
    val services: List<TTSProviderSetting>,
    val selectedId: String,
    val recovered: Boolean,
)

interface TtsConfigDataSource {
    fun services(): List<TTSProviderSetting>
    fun saveServices(services: List<TTSProviderSetting>)
    fun selectedId(): String
    fun saveSelectedId(id: String)
    fun consumeRecoveryNotice(): Boolean
}

class TtsConfigController(
    private val dataSource: TtsConfigDataSource,
) {
    fun load(): TtsConfigState {
        val services = dataSource.services()
        val selected = dataSource.selectedId().takeIf { id -> services.any { it.id == id } }
            ?: services.first().id
        return TtsConfigState(services, selected, dataSource.consumeRecoveryNotice())
    }

    fun replace(state: TtsConfigState, services: List<TTSProviderSetting>): TtsConfigState {
        require(services.isNotEmpty())
        dataSource.saveServices(services)
        val selected = state.selectedId.takeIf { id -> services.any { it.id == id } } ?: services.first().id
        if (selected != state.selectedId) dataSource.saveSelectedId(selected)
        return state.copy(services = services, selectedId = selected)
    }

    fun add(state: TtsConfigState, service: TTSProviderSetting): TtsConfigState =
        replace(state, state.services + service)

    fun remove(state: TtsConfigState, id: String): TtsConfigState {
        if (state.services.size <= 1) return state
        return replace(state, state.services.filterNot { it.id == id })
    }

    fun select(state: TtsConfigState, id: String): TtsConfigState {
        if (state.services.none { it.id == id }) return state
        dataSource.saveSelectedId(id)
        return state.copy(selectedId = id)
    }
}
