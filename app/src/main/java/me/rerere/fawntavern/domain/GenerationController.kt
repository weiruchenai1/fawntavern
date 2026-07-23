package me.rerere.fawntavern.domain

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.ChatApi
import me.rerere.fawntavern.data.chat.ChatMessage

/**
 * 一次流式生成的执行器：把 AI 回复流式填充进 [run] 传入的目标消息 [genMessage]（可为追加的新
 * 空消息，或已开好新版本的重答消息），流式期间以约 60ms 一帧的节流频率通过 onUpdate 发布中间消息，
 * 返回最终消息。不做 IO 落盘、不持有 UI 状态 —— 调用方负责保存与 generating 标志。
 */
internal class GenerationController {

    private val stopFlag = AtomicBoolean(false)

    /** 用户主动停止当前生成 */
    fun stop() {
        stopFlag.set(true)
    }

    suspend fun run(
        promptHistory: List<ChatMessage>,
        genMessage: ChatMessage,
        provider: ApiProvider,
        modelId: String,
        built: PromptBuilder.Built,
        filesDir: java.io.File?,
        streaming: Boolean,
        errorText: (Exception) -> String,
        onUpdate: (ChatMessage) -> Unit,
    ): ChatMessage = coroutineScope {
        stopFlag.set(false)
        var cur = genMessage
        onUpdate(cur)
        var reasoningStart = 0L
        var reasoningEnd = 0L  // 正文首个 token 到达时定格思考耗时，避免"思考了 xx 秒"在正文输出期间持续增长
        // 累加缓冲：SSE 在 IO 线程高频回调，UI 按帧节流刷新，避免每个 token 都重组。
        // lock 保护 StringBuilder：IO 线程 append 与主线程 toString 并发
        val lock = Any()
        val buf = StringBuilder()
        val reasoningBuf = StringBuilder()
        val dirty = AtomicBoolean(false)
        fun flushToUi() {
            val (content, reasoning) = synchronized(lock) { buf.toString() to reasoningBuf.toString() }
            val reasoningMs = when {
                reasoningStart == 0L -> 0L
                reasoningEnd != 0L -> reasoningEnd - reasoningStart
                else -> System.currentTimeMillis() - reasoningStart
            }
            cur = cur.copy(content = content, reasoning = reasoning, reasoningMs = reasoningMs)
            onUpdate(cur)
        }
        // 流式模式下的节流刷新协程（约每 60ms 一帧），由 refresher.cancel() 终止
        val refresher = if (streaming) launch {
            while (true) {
                if (dirty.getAndSet(false)) flushToUi()
                delay(60)
            }
        } else null
        try {
            val apiMessages = PromptBuilder.assemble(built, promptHistory, filesDir)
            // 记录本次组装出的完整 prompt（日志开关关闭时为空操作）
            PromptLog.record(built, provider, modelId, apiMessages)
            ChatApi.streamChat(
                provider = provider,
                modelId = modelId,
                messages = apiMessages,
                params = built.genParams,
                isCancelled = { stopFlag.get() },
            ) { c, r ->
                if (stopFlag.get()) throw ChatApi.Stopped()
                if (r.isNotEmpty() && reasoningStart == 0L) reasoningStart = System.currentTimeMillis()
                if (c.isNotEmpty() && reasoningStart != 0L && reasoningEnd == 0L) reasoningEnd = System.currentTimeMillis()
                synchronized(lock) { buf.append(c); reasoningBuf.append(r) }
                dirty.set(true)
            }
        } catch (_: ChatApi.Stopped) {
        } catch (e: Exception) {
            synchronized(lock) {
                if (buf.isNotEmpty()) buf.append("\n\n")
                buf.append(errorText(e))
            }
        }
        refresher?.cancel()
        flushToUi()  // 收尾：把缓冲的最终内容全部写入
        // 将最终内容同步回当前版本（重答开的新版本）
        if (cur.alts.isNotEmpty()) {
            val alts = cur.alts.toMutableList()
            alts[cur.altIdx] = alts[cur.altIdx].copy(
                content = cur.content, reasoning = cur.reasoning,
                model = cur.model, reasoningMs = cur.reasoningMs)
            cur = cur.copy(alts = alts)
        }
        onUpdate(cur)
        cur
    }
}
