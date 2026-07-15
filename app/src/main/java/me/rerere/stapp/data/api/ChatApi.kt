package me.rerere.stapp.data.api

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 流式聊天补全入口：按 provider.type 路由到对应的 [ProviderAdapter]
 * （openai 兼容 / google / claude），并负责 IO 调度与停止信号。
 */
object ChatApi {

    /** 用户主动停止生成时抛出 */
    class Stopped : Exception()

    /**
     * 流式聊天补全。messages 为拼装完成的完整消息数组（PromptBuilder 产出），
     * params 为采样参数（来自关联预设，null = 服务端默认）。
     * onDelta 在 IO 线程回调增量 (正文, 思考内容)。
     * 返回前保证连接已关闭；HTTP 错误抛 IllegalStateException；用户停止抛 [Stopped]。
     *
     * 停止有两条路径：读循环每收到一行检查一次 isCancelled（快路径）；
     * 另有监视协程轮询 isCancelled 并 cancel() OkHttp Call —— 覆盖模型思考期间
     * 服务器长时间不发数据、线程阻塞在 read() 上的情况。
     */
    suspend fun streamChat(
        provider: ApiProvider,
        modelId: String,
        messages: List<ApiMessage>,
        params: GenParams?,
        isCancelled: () -> Boolean,
        onDelta: (content: String, reasoning: String) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val stopped: () -> Unit = { if (isCancelled()) throw Stopped() }
        val callRef = AtomicReference<okhttp3.Call?>(null)
        val watcher = launch {
            while (isActive) {
                if (isCancelled()) {
                    callRef.get()?.cancel()
                    break
                }
                delay(100)
            }
        }
        try {
            adapterFor(provider.type).stream(
                provider, modelId, messages, params, onDelta, stopped,
                onCall = { callRef.set(it) },
            )
        } catch (e: IOException) {
            // Call.cancel() 导致的读中断归一为 Stopped；真实网络错误原样抛出
            if (isCancelled()) throw Stopped() else throw e
        } finally {
            watcher.cancel()
        }
    }

    private fun adapterFor(type: String): ProviderAdapter = when (type) {
        "google" -> GoogleAdapter
        "claude" -> ClaudeAdapter
        else -> OpenAiAdapter
    }
}
