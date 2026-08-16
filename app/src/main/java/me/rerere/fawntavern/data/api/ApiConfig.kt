package me.rerere.fawntavern.data.api

data class ApiProvider(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val type: String = "openai",  // openai, google, claude
    val baseUrl: String = "",
    val apiKey: String = "",
    val enabled: Boolean = true,
    val useResponseApi: Boolean = false,    // OpenAI Responses API (/responses)
    val models: List<ModelInfo> = emptyList(),
    val balanceEnabled: Boolean = false,     // 余额查询
    val balancePath: String = "",            // 余额 API 路径，如 /user/balance
    val balanceJsonKey: String = "",         // 余额结果 JSON 键，如 balance_infos[0].total_balance
) {
    fun model(modelId: String): ModelInfo? = models.find { it.id == modelId }
}

data class ApiConfig(
    val providers: List<ApiProvider> = emptyList(),
    val currentModel: String = "",  // "providerId::modelId"
)

/**
 * 校正当前选中模型：提供商被删除/禁用、或该模型已从其列表移除时清空选择。
 * enabled 只过滤选择列表，不清理旧选择的话，禁用后的提供商仍会被拿去发请求。
 */
fun ApiConfig.withValidCurrentModel(): ApiConfig {
    if (currentModel.isBlank()) return this
    val prov = providers.find { it.id == currentModel.substringBefore("::") }
    val model = currentModel.substringAfter("::", "")
    return if (prov != null && prov.enabled && prov.model(model) != null) this
           else copy(currentModel = "")
}
