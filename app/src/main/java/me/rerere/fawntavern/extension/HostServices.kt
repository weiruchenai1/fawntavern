package me.rerere.fawntavern.extension

import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ChatApi
import me.rerere.fawntavern.data.api.GenParams
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.domain.chat.ChatDataRepository

/** 宿主能力的 Android 组合实现；扩展模块不接触数据库和设置实现。 */
internal class HostServices(
    private val config: ApiConfig,
    private val chatRepository: ChatDataRepository,
    private val modelPreference: (ExtensionModelPurpose) -> String?,
) : ExtensionServices {

    override suspend fun callModel(messages: List<ApiMessage>, params: GenParams?, modelId: String?): String {
        val (provider, model) = resolve(modelId) ?: throw IllegalStateException("扩展 callModel：未配置可用模型")
        val content = StringBuilder()
        ChatApi.streamChat(
            provider = provider,
            modelId = model,
            messages = messages,
            params = params,
            isCancelled = { false },
            onDelta = { delta, _ -> content.append(delta) },
        )
        return content.toString()
    }

    override fun getExtState(session: ChatSession, extId: String): String = session.extState[extId].orEmpty()

    override suspend fun saveExtState(sessionId: String, extId: String, state: String) {
        val latest = chatRepository.get(sessionId) ?: return
        val next = latest.extState.toMutableMap()
        if (state.isBlank()) next.remove(extId) else next[extId] = state
        chatRepository.save(latest.copy(extState = next, updatedAt = System.currentTimeMillis()))
    }

    override fun preferredModel(purpose: ExtensionModelPurpose): String? = modelPreference(purpose)

    private fun resolve(modelId: String?): Pair<ApiProvider, String>? {
        val spec = modelId?.takeIf(String::isNotBlank) ?: config.currentModel
        if (spec.isBlank()) return null
        val hasProvider = spec.contains("::")
        val providerId = if (hasProvider) spec.substringBefore("::") else config.currentModel.substringBefore("::", "")
        val model = if (hasProvider) spec.substringAfter("::", "") else spec
        if (providerId.isBlank() || model.isBlank()) return null
        val provider = config.providers.find { it.id == providerId && it.enabled } ?: return null
        return provider to model
    }
}
