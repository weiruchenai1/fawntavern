package me.rerere.fawntavern.ui.translator

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.settings.DefaultModelPrompts

data class TranslationDefaults(
    val modelSpec: String,
    val prompt: String,
)

interface TranslatorDataSource {
    fun defaults(): TranslationDefaults
    fun saveModel(modelSpec: String)
    suspend fun stream(
        provider: ApiProvider,
        modelId: String,
        messages: List<ApiMessage>,
        isCancelled: () -> Boolean,
        onDelta: (String) -> Unit,
    )
}

class TranslatorController(
    private val dataSource: TranslatorDataSource,
) {
    fun defaults(fallbackModelSpec: String): TranslationDefaults {
        val stored = dataSource.defaults()
        return TranslationDefaults(
            modelSpec = stored.modelSpec.ifBlank { fallbackModelSpec },
            prompt = stored.prompt.ifBlank { DefaultModelPrompts.TRANSLATION },
        )
    }

    fun saveModel(modelSpec: String) = dataSource.saveModel(modelSpec)

    suspend fun translate(
        provider: ApiProvider,
        modelId: String,
        sourceText: String,
        language: String,
        prompt: String,
        isCancelled: () -> Boolean,
        onUpdate: (String) -> Unit,
    ) = coroutineScope {
        val updates = Channel<String>(Channel.CONFLATED)
        val collector = launch {
            for (text in updates) onUpdate(text)
        }
        try {
            val result = StringBuilder()
            dataSource.stream(
                provider = provider,
                modelId = modelId,
                messages = listOf(
                    ApiMessage(role = "system", content = prompt.replace("{language}", language)),
                    ApiMessage(role = "user", content = sourceText),
                ),
                isCancelled = isCancelled,
            ) { content ->
                if (content.isNotEmpty()) {
                    result.append(content)
                    updates.trySend(result.toString())
                }
            }
        } finally {
            updates.close()
            collector.join()
        }
    }
}
