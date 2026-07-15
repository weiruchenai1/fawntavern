package me.rerere.stapp.data.api

data class ApiProvider(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val type: String = "openai",  // openai, google, claude
    val baseUrl: String = "",
    val apiKey: String = "",
    val enabled: Boolean = true,
    val models: List<String> = emptyList(),  // 模型 ID
    val balanceEnabled: Boolean = false,     // 余额查询
    val balancePath: String = "",            // 余额 API 路径，如 /user/balance
    val balanceJsonKey: String = "",         // 余额结果 JSON 键，如 balance_infos[0].total_balance
)

data class ApiConfig(
    val providers: List<ApiProvider> = emptyList(),
    val currentModel: String = "",  // "providerId::modelId"
)
