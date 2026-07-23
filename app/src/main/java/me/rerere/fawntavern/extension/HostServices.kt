package me.rerere.fawntavern.extension

import android.content.Context
import me.rerere.fawntavern.data.api.ApiConfig
import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ChatApi
import me.rerere.fawntavern.data.api.GenParams
import me.rerere.fawntavern.data.chat.ChatRepository
import me.rerere.fawntavern.data.chat.ChatSession

/**
 * [ExtensionServices] 的宿主侧默认实现。由 ViewModel 用当前 Context + ApiConfig 构造
 * （ApiConfig 会随设置变化，故在每次生成/回调时用最新值新建一个）。
 */
class HostServices(
    private val ctx: Context,
    private val config: ApiConfig,
) : ExtensionServices {

    /** 一次性补全：底层只有流式，这里累积 delta 成整段（不可取消，isCancelled 恒 false）。 */
    override suspend fun callModel(messages: List<ApiMessage>, params: GenParams?, modelId: String?): String {
        val (provider, model) = resolve(modelId) ?: throw IllegalStateException("扩展 callModel：未配置可用模型")
        val sb = StringBuilder()
        ChatApi.streamChat(
            provider = provider,
            modelId = model,
            messages = messages,
            params = params,
            isCancelled = { false },
            onDelta = { content, _ -> sb.append(content) },
        )
        return sb.toString()
    }

    override fun getExtState(session: ChatSession, extId: String): String = session.extState[extId] ?: ""

    /** 读最新会话 → 只补本扩展字段 → 落盘，避免覆盖并发写入的其它字段/消息。 */
    override suspend fun saveExtState(sessionId: String, extId: String, state: String) {
        val latest = ChatRepository.get(ctx, sessionId) ?: return
        val map = latest.extState.toMutableMap()
        if (state.isBlank()) map.remove(extId) else map[extId] = state
        ChatRepository.save(ctx, latest.copy(extState = map, updatedAt = System.currentTimeMillis()))
    }

    /** 解析 "providerId::modelId"（或裸 modelId 用当前 provider）；空则回退当前配置的模型。 */
    private fun resolve(modelId: String?): Pair<ApiProvider, String>? {
        val spec = modelId?.takeIf { it.isNotBlank() } ?: config.currentModel
        if (spec.isBlank()) return null
        val hasProv = spec.contains("::")
        val provId = if (hasProv) spec.substringBefore("::") else config.currentModel.substringBefore("::", "")
        val model = if (hasProv) spec.substringAfter("::", "") else spec
        if (provId.isBlank() || model.isBlank()) return null
        val prov = config.providers.find { it.id == provId } ?: return null
        return prov to model
    }
}
