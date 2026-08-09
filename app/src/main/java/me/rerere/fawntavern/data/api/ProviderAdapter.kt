package me.rerere.fawntavern.data.api

/** 一轮流式补全的收尾信息：模型若发起了工具调用，由上层执行后回传再开下一轮 */
data class StreamEnd(
    /** 模型本轮发起的工具调用（已解析完整参数）；为空 = 正常结束 */
    val toolCalls: List<ApiToolCall> = emptyList(),
    /** 协议私有的 assistant 原始内容块 JSON（见 [ApiMessage.rawBlocks]），无需回显的协议为空串 */
    val rawBlocks: String = "",
)

/**
 * 某一种 API 协议的流式聊天实现。实现类只关心协议编解码，
 * 网络与 SSE 解析统一走 [SseClient]，停止/线程调度由上层 [ChatApi] 负责。
 */
internal interface ProviderAdapter {

    /**
     * 阻塞式流式补全（调用方保证运行在 IO 线程）。
     * messages 是拼装完成的完整消息数组（system/user/assistant，可含图片与工具调用轮次）；
     * params 为采样参数（null = 全部用服务端默认）；tools 为下发给模型的工具声明（空 = 不发）。
     * 实现须在收尾处套上模型的自定义请求头/请求体（[applyHeaders] / [applyCustomBodies]），
     * 让它们能覆盖协议自己填的字段。
     * onDelta 回调增量 (正文, 思考内容)；stopped 应在处理每条事件前调用；
     * onCall 透传 OkHttp Call，供上层在阻塞读期间主动取消。
     * 返回本轮收尾信息：含未执行的工具调用时由上层执行并续轮。
     */
    fun stream(
        provider: ApiProvider,
        model: ModelInfo,
        messages: List<ApiMessage>,
        params: GenParams?,
        tools: List<ToolSpec>,
        onDelta: (content: String, reasoning: String) -> Unit,
        stopped: () -> Unit,
        onCall: (okhttp3.Call) -> Unit,
    ): StreamEnd
}
