package me.rerere.fawntavern.ui.api

import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ConnectionTester
import me.rerere.fawntavern.data.api.ModelApi
import me.rerere.fawntavern.data.api.ModelInfo

internal object AndroidApiRuntime : ApiRuntime {
    override suspend fun models(provider: ApiProvider): List<ModelInfo> = ModelApi.listModels(provider)
    override suspend fun balance(provider: ApiProvider): String = ModelApi.getBalance(provider)

    override suspend fun testNonStreaming(provider: ApiProvider, modelId: String): String =
        ConnectionTester.testNonStreaming(provider, modelId)

    override suspend fun testStreaming(provider: ApiProvider, modelId: String, onDelta: (String) -> Unit) =
        ConnectionTester.testStreaming(provider, modelId, onDelta)

    override suspend fun testToolCall(provider: ApiProvider, modelId: String): ApiToolTestResult =
        ConnectionTester.testToolCall(provider, modelId).let {
            ApiToolTestResult(it.toolName, it.args, it.text)
        }
}
