package me.rerere.stapp.data.api

/**
 * 某一种 API 协议的流式聊天实现。实现类只关心协议编解码，
 * 网络与 SSE 解析统一走 [SseClient]，停止/线程调度由上层 [ChatApi] 负责。
 */
internal interface ProviderAdapter {

    /**
     * 阻塞式流式补全（调用方保证运行在 IO 线程）。
     * messages 是拼装完成的完整消息数组（system/user/assistant，可含图片）；
     * params 为采样参数（null = 全部用服务端默认）。
     * onDelta 回调增量 (正文, 思考内容)；stopped 应在处理每条事件前调用；
     * onCall 透传 OkHttp Call，供上层在阻塞读期间主动取消。
     */
    fun stream(
        provider: ApiProvider,
        modelId: String,
        messages: List<ApiMessage>,
        params: GenParams?,
        onDelta: (content: String, reasoning: String) -> Unit,
        stopped: () -> Unit,
        onCall: (okhttp3.Call) -> Unit,
    )
}
