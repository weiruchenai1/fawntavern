package me.rerere.fawntavern.extension

import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.data.api.GenParams
import me.rerere.fawntavern.data.chat.ChatSession

/**
 * 宿主提供给扩展的服务面（由 ViewModel 侧实现，持有 API 配置 / 当前模型 / Context）。
 *
 * - [callModel]：一次性补全。底层 API 只有流式（[me.rerere.fawntavern.data.api.ChatApi.streamChat]），
 *   这里把流式 delta 累积成整段返回。[modelId] 为空时使用当前会话所用模型。当前仅原生扩展
 *   可以访问该服务；隔离插件进程不暴露模型调用。
 * - [getExtState] / [saveExtState]：读写本扩展在某会话的状态 blob（JSON 串）。保存采用
 *   "读最新会话 → 只补本扩展字段 → 落盘"，避免覆盖并发写入的其他字段。
 */
interface ExtensionServices {
    suspend fun callModel(
        messages: List<ApiMessage>,
        params: GenParams? = null,
        modelId: String? = null,
    ): String

    fun getExtState(session: ChatSession, extId: String): String

    suspend fun saveExtState(sessionId: String, extId: String, state: String)
}
