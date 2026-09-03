package me.rerere.fawntavern.ui.api

import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ModelInfo

data class ApiToolTestResult(
    val toolName: String,
    val arguments: String,
    val text: String,
)

interface ApiRuntime {
    suspend fun models(provider: ApiProvider): List<ModelInfo>
    suspend fun balance(provider: ApiProvider): String
    suspend fun testNonStreaming(provider: ApiProvider, modelId: String): String
    suspend fun testStreaming(provider: ApiProvider, modelId: String, onDelta: (String) -> Unit)
    suspend fun testToolCall(provider: ApiProvider, modelId: String): ApiToolTestResult
}
