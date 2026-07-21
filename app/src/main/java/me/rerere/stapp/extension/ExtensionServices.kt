package me.rerere.stapp.extension

import me.rerere.stapp.data.api.ApiMessage
import me.rerere.stapp.data.api.GenParams
import me.rerere.stapp.data.chat.ChatSession

/**
 * 宿主提供给扩展的服务面（由 ViewModel 侧实现，持有 API 配置 / 当前模型 / Context）。
 *
 * - [callModel]：一次性补全。底层 API 只有流式（[me.rerere.stapp.data.api.ChatApi.streamChat]），
 *   这里把流式 delta 累积成整段返回。原生扩展用 suspend；Phase 2 的 QuickJS 桥（无 Promise）
 *   会在 JS 线程上以同步阻塞方式适配。[modelId] 为空时用当前会话所用模型。
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
